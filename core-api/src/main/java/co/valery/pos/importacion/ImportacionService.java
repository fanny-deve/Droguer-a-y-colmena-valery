package co.valery.pos.importacion;

import co.valery.pos.auditoria.AuditService;
import co.valery.pos.catalogo.CatalogoDtos.ProductoRequest;
import co.valery.pos.catalogo.ProductoService;
import co.valery.pos.common.ApiException;
import co.valery.pos.common.PageResponse;
import co.valery.pos.inventario.AlertaService;
import co.valery.pos.inventario.InventarioService;
import co.valery.pos.security.CurrentUser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Carga masiva desde CSV (RF-23): catálogo con existencias iniciales e histórico de ventas del cuaderno.
 * Cada fila se procesa de forma independiente: las válidas se importan y las inválidas se reportan con fila, campo y causa.
 * Nada se importa a medias: una fila de catálogo (producto + lote inicial) se guarda completa o no se guarda.
 */
@Service
public class ImportacionService {
    private static final Logger log = LoggerFactory.getLogger(ImportacionService.class);
    private static final int MAX_FILAS = 5000;
    private static final int MAX_ERRORES_GUARDADOS = 1000;
    private static final Pattern CODIGO = Pattern.compile("^[A-Za-z0-9._-]{3,30}$");
    private static final List<DateTimeFormatter> FORMATOS_FECHA = List.of(DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d/M/uuuu"), DateTimeFormatter.ofPattern("d-M-uuuu"));

    public record ErrorFila(int fila, String campo, String motivo) {}

    public record ResultadoImportacion(UUID id, String tipo, String archivo, int totalFilas, int filasOk, int filasError,
                                       List<ErrorFila> errores, boolean erroresTruncados, String mensaje) {}

    public record ImportacionDto(UUID id, String tipo, String archivo, int totalFilas, int filasOk, int filasError, String usuario,
                                 OffsetDateTime creadoEn) {}

    private interface ProcesadorFila {
        List<ErrorFila> procesar(CsvLector.Fila fila, UUID importacionId);
    }

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final ProductoService productos;
    private final InventarioService inventario;
    private final AlertaService alertas;
    private final TransactionTemplate porFila;

    public ImportacionService(JdbcClient jdbc, AuditService audit, ProductoService productos, InventarioService inventario,
                              AlertaService alertas, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.productos = productos;
        this.inventario = inventario;
        this.alertas = alertas;
        this.porFila = new TransactionTemplate(tm);
        this.porFila.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // =====================================================================================================
    // Plantillas
    // =====================================================================================================

    public String plantilla(String tipo) {
        return switch (tipo.toLowerCase(Locale.ROOT)) {
            case "catalogo" -> "codigo;nombre;categoria;unidad;precio_venta;costo;stock_minimo;punto_reorden;maneja_vencimiento;proveedor;stock_inicial;lote;fecha_vencimiento;costo_lote\n"
                    + "7701234567890;Acetaminofén 500 mg x 100;Farmacia - Medicamentos de venta libre;CAJA;12500;8000;5;10;si;;40;L2025-01;2027-06-30;8000\n";
            case "historico" -> "codigo;fecha;unidades;ingresos\n7701234567890;2025-03-15;12;150000\n";
            default -> throw ApiException.notFound("PLANTILLA_NO_ENCONTRADA", "Plantillas disponibles: catalogo, historico");
        };
    }

    // =====================================================================================================
    // Importación de catálogo + existencias iniciales
    // =====================================================================================================

    public ResultadoImportacion importarCatalogo(String archivo, byte[] contenido) {
        return procesar("CATALOGO_STOCK", archivo, contenido, List.of("codigo", "nombre", "categoria", "precio_venta"), this::filaCatalogo, true);
    }

    private List<ErrorFila> filaCatalogo(CsvLector.Fila f, UUID importacionId) {
        List<ErrorFila> e = new ArrayList<>();
        String codigo = f.get("codigo");
        String nombre = f.get("nombre");
        String categoria = f.get("categoria");
        if (codigo == null) e.add(new ErrorFila(f.numero(), "codigo", "es obligatorio"));
        else if (!CODIGO.matcher(codigo).matches()) e.add(new ErrorFila(f.numero(), "codigo", "debe tener 3 a 30 caracteres: letras, números, punto o guion"));
        if (nombre == null) e.add(new ErrorFila(f.numero(), "nombre", "es obligatorio"));
        else if (nombre.length() < 2 || nombre.length() > 150) e.add(new ErrorFila(f.numero(), "nombre", "debe tener entre 2 y 150 caracteres"));
        BigDecimal precio = decimal(f, "precio_venta", true, true, e);
        BigDecimal costo = decimal(f, "costo", false, true, e);
        BigDecimal minimo = decimal(f, "stock_minimo", false, false, e);
        BigDecimal reorden = decimal(f, "punto_reorden", false, false, e);
        BigDecimal stock = decimal(f, "stock_inicial", false, false, e);
        BigDecimal costoLote = decimal(f, "costo_lote", false, true, e);
        Boolean maneja = booleano(f, "maneja_vencimiento", e);
        LocalDate vence = fecha(f, "fecha_vencimiento", e);

        Integer categoriaId = null;
        if (categoria != null) {
            categoriaId = jdbc.sql("select id from categorias where lower(nombre) = lower(:n) and activa").param("n", categoria)
                    .query(Integer.class).optional().orElse(null);
            if (categoriaId == null) e.add(new ErrorFila(f.numero(), "categoria", "No existe la categoría '" + categoria + "'"));
        } else {
            e.add(new ErrorFila(f.numero(), "categoria", "es obligatoria"));
        }
        UUID proveedorId = null;
        if (f.get("proveedor") != null) {
            proveedorId = jdbc.sql("select id from proveedores where lower(nombre) = lower(:n) and activo and eliminado_en is null")
                    .param("n", f.get("proveedor")).query(UUID.class).optional().orElse(null);
            if (proveedorId == null) e.add(new ErrorFila(f.numero(), "proveedor", "No existe el proveedor activo '" + f.get("proveedor") + "'"));
        }
        boolean venc = maneja == null || maneja;
        BigDecimal cantidad = stock == null ? BigDecimal.ZERO : stock;
        if (cantidad.signum() > 0 && venc && vence == null) {
            e.add(new ErrorFila(f.numero(), "fecha_vencimiento", "es obligatoria cuando el producto maneja vencimiento y hay stock inicial"));
        }
        if (reorden != null && minimo != null && reorden.compareTo(minimo) < 0) {
            e.add(new ErrorFila(f.numero(), "punto_reorden", "no puede ser menor al stock mínimo"));
        }
        if (!e.isEmpty()) return e;

        final Integer catId = categoriaId;
        final UUID provId = proveedorId;
        final BigDecimal costoFinal = costo == null ? BigDecimal.ZERO : costo;
        try {
            porFila.executeWithoutResult(st -> {
                var creado = productos.crear(new ProductoRequest(codigo, nombre, f.get("descripcion"), catId,
                        f.get("unidad") == null ? "UNIDAD" : f.get("unidad"), precio, costoFinal, venc, minimo, reorden, provId));
                if (cantidad.signum() > 0) {
                    String lote = f.get("lote") == null ? "INI-" + codigo : f.get("lote");
                    inventario.registrarCargaInicial(creado.id(), lote, vence, cantidad, costoLote == null ? costoFinal : costoLote);
                }
            });
        } catch (ApiException ex) {
            e.add(new ErrorFila(f.numero(), campoDe(ex.code()), ex.getMessage()));
        } catch (DataAccessException ex) {
            log.warn("Fila {} del catálogo rechazada por la base de datos: {}", f.numero(), ex.getMostSpecificCause().getMessage());
            e.add(new ErrorFila(f.numero(), null, "Los datos no cumplen las reglas de integridad del sistema"));
        }
        return e;
    }

    private static String campoDe(String codigo) {
        return switch (codigo) {
            case "CODIGO_DUPLICADO", "CODIGO_EAN_INVALIDO" -> "codigo";
            case "UNIDAD_INVALIDA" -> "unidad";
            case "PUNTO_REORDEN_INVALIDO" -> "punto_reorden";
            case "CATEGORIA_NO_ENCONTRADA", "CATEGORIA_INACTIVA" -> "categoria";
            case "PROVEEDOR_NO_ENCONTRADO", "PROVEEDOR_INACTIVO" -> "proveedor";
            default -> null;
        };
    }

    // =====================================================================================================
    // Importación del histórico de ventas del cuaderno
    // =====================================================================================================

    public ResultadoImportacion importarHistorico(String archivo, byte[] contenido) {
        return procesar("HISTORICO_VENTAS", archivo, contenido, List.of("codigo", "fecha", "unidades"), this::filaHistorico, false);
    }

    private List<ErrorFila> filaHistorico(CsvLector.Fila f, UUID importacionId) {
        List<ErrorFila> e = new ArrayList<>();
        String codigo = f.get("codigo");
        if (codigo == null) e.add(new ErrorFila(f.numero(), "codigo", "es obligatorio"));
        LocalDate fecha = fecha(f, "fecha", e);
        if (f.get("fecha") == null) e.add(new ErrorFila(f.numero(), "fecha", "es obligatoria"));
        else if (fecha != null && (fecha.isAfter(LocalDate.now(AlertaService.ZONA)) || fecha.isBefore(LocalDate.of(2000, 1, 1)))) {
            e.add(new ErrorFila(f.numero(), "fecha", "debe estar entre 2000-01-01 y hoy"));
        }
        BigDecimal unidades = decimal(f, "unidades", true, false, e);
        BigDecimal ingresos = decimal(f, "ingresos", false, true, e);
        UUID productoId = null;
        if (codigo != null) {
            productoId = jdbc.sql("select id from productos where upper(codigo) = upper(:c)").param("c", codigo).query(UUID.class).optional().orElse(null);
            if (productoId == null) e.add(new ErrorFila(f.numero(), "codigo", "No existe un producto con el código '" + codigo + "'"));
        }
        if (!e.isEmpty()) return e;
        jdbc.sql("""
                insert into historico_ventas(producto_id, fecha, unidades, ingresos, fuente, importacion_id)
                values (:p, :f, :u, :i, 'CUADERNO', :imp)
                on conflict (producto_id, fecha, fuente) do update set unidades = excluded.unidades, ingresos = excluded.ingresos,
                       importacion_id = excluded.importacion_id
                """).param("p", productoId).param("f", fecha).param("u", unidades).param("i", ingresos == null ? BigDecimal.ZERO : ingresos)
                .param("imp", importacionId).update();
        return e;
    }

    // =====================================================================================================
    // Proceso común
    // =====================================================================================================

    private ResultadoImportacion procesar(String tipo, String archivo, byte[] contenido, List<String> requeridas, ProcesadorFila procesador,
                                          boolean evaluarAlertas) {
        var actor = CurrentUser.require();
        CsvLector.Archivo csv = CsvLector.leer(contenido, MAX_FILAS);
        List<String> faltantes = requeridas.stream().filter(c -> !csv.encabezados().contains(c)).toList();
        if (!faltantes.isEmpty()) {
            throw ApiException.badRequest("ARCHIVO_INVALIDO", "Faltan columnas obligatorias: " + String.join(", ", faltantes)
                    + ". Descargue la plantilla para ver el formato esperado.");
        }
        if (csv.filas().isEmpty()) {
            throw ApiException.badRequest("ARCHIVO_VACIO", "El archivo no contiene filas de datos");
        }
        String nombre = archivo == null ? "archivo.csv" : archivo.replaceAll("[\\\\/]", "_");
        nombre = nombre.substring(0, Math.min(nombre.length(), 200));
        UUID importacionId = jdbc.sql("""
                insert into importaciones(tipo, archivo_nombre, total_filas, filas_ok, filas_error, usuario_id)
                values (:t, :a, :n, 0, 0, :u) returning id
                """).param("t", tipo).param("a", nombre).param("n", csv.filas().size()).param("u", actor.id())
                .query((rs, i) -> rs.getObject(1, UUID.class)).single();

        List<ErrorFila> errores = new ArrayList<>();
        int ok = 0, malas = 0;
        for (CsvLector.Fila f : csv.filas()) {
            List<ErrorFila> r;
            try {
                r = procesador.procesar(f, importacionId);
            } catch (RuntimeException ex) {
                log.error("Error inesperado importando la fila {}", f.numero(), ex);
                r = List.of(new ErrorFila(f.numero(), null, "Error inesperado al procesar la fila"));
            }
            if (r.isEmpty()) ok++;
            else {
                malas++;
                errores.addAll(r);
            }
        }
        jdbc.sql("update importaciones set filas_ok = :ok, filas_error = :err where id = :id").param("ok", ok).param("err", malas)
                .param("id", importacionId).update();
        boolean truncado = errores.size() > MAX_ERRORES_GUARDADOS;
        for (ErrorFila er : errores.subList(0, Math.min(errores.size(), MAX_ERRORES_GUARDADOS))) {
            jdbc.sql("insert into importacion_errores(importacion_id, fila, campo, motivo) values (:i, :f, cast(:c as text), :m)")
                    .param("i", importacionId).param("f", er.fila()).param("c", er.campo()).param("m", er.motivo()).update();
        }
        if (evaluarAlertas && ok > 0) alertas.evaluarTodo();
        audit.registrar("IMPORTAR_" + (tipo.equals("CATALOGO_STOCK") ? "CATALOGO" : "HISTORICO"), "importaciones", importacionId.toString(),
                Map.of("archivo", nombre, "filas", csv.filas().size(), "ok", ok, "errores", malas));
        String mensaje = malas == 0 ? "Importación completa: " + ok + " filas." : ok + " filas importadas y " + malas + " rechazadas. Corrija las filas con error y vuelva a cargarlas.";
        return new ResultadoImportacion(importacionId, tipo, nombre, csv.filas().size(), ok, malas,
                errores.subList(0, Math.min(errores.size(), 200)), errores.size() > 200 || truncado, mensaje);
    }

    @Transactional(readOnly = true)
    public PageResponse<ImportacionDto> listar(int page, int size) {
        int tam = PageResponse.limitarTamano(size);
        int pag = Math.max(page, 0);
        long total = jdbc.sql("select count(*) from importaciones").query(Long.class).single();
        List<ImportacionDto> items = jdbc.sql("""
                select i.id, i.tipo, i.archivo_nombre, i.total_filas, i.filas_ok, i.filas_error, u.username, i.creado_en
                from importaciones i join usuarios u on u.id = i.usuario_id order by i.creado_en desc limit :l offset :o
                """).param("l", tam).param("o", pag * tam)
                .query((rs, i) -> new ImportacionDto(rs.getObject("id", UUID.class), rs.getString("tipo"), rs.getString("archivo_nombre"),
                        rs.getInt("total_filas"), rs.getInt("filas_ok"), rs.getInt("filas_error"), rs.getString("username"),
                        rs.getObject("creado_en", OffsetDateTime.class))).list();
        return new PageResponse<>(items, pag, tam, total);
    }

    @Transactional(readOnly = true)
    public List<ErrorFila> errores(UUID importacionId) {
        if (jdbc.sql("select count(*) from importaciones where id = :id").param("id", importacionId).query(Long.class).single() == 0) {
            throw ApiException.notFound("IMPORTACION_NO_ENCONTRADA", "Importación no encontrada");
        }
        return jdbc.sql("select fila, campo, motivo from importacion_errores where importacion_id = :id order by fila, id")
                .param("id", importacionId).query((rs, i) -> new ErrorFila(rs.getInt("fila"), rs.getString("campo"), rs.getString("motivo"))).list();
    }

    // ---------- Conversión de valores ----------

    private static BigDecimal decimal(CsvLector.Fila f, String col, boolean obligatorio, boolean dinero, List<ErrorFila> e) {
        String v = f.get(col);
        if (v == null) {
            if (obligatorio) e.add(new ErrorFila(f.numero(), col, "es obligatorio"));
            return null;
        }
        try {
            String s = v.replace(" ", "").replace("$", "");
            if (dinero && s.matches("^\\d{1,3}(\\.\\d{3})+$")) s = s.replace(".", "");               // 150.000 (miles, estilo colombiano)
            else if (s.contains(",") && s.contains(".")) s = s.replace(".", "").replace(",", ".");   // 1.500,50
            else if (s.contains(",")) s = s.replace(",", ".");                                    // 12,5
            BigDecimal n = new BigDecimal(s);
            if (n.signum() < 0) {
                e.add(new ErrorFila(f.numero(), col, "no puede ser negativo"));
                return null;
            }
            return n;
        } catch (NumberFormatException ex) {
            e.add(new ErrorFila(f.numero(), col, "'" + v + "' no es un número válido"));
            return null;
        }
    }

    private static Boolean booleano(CsvLector.Fila f, String col, List<ErrorFila> e) {
        String v = f.get(col);
        if (v == null) return null;
        String s = CsvLector.normalizar(v);
        if (List.of("si", "s", "true", "1", "yes", "y").contains(s)) return true;
        if (List.of("no", "n", "false", "0").contains(s)) return false;
        e.add(new ErrorFila(f.numero(), col, "'" + v + "' debe ser si o no"));
        return null;
    }

    private static LocalDate fecha(CsvLector.Fila f, String col, List<ErrorFila> e) {
        String v = f.get(col);
        if (v == null) return null;
        for (DateTimeFormatter fmt : FORMATOS_FECHA) {
            try {
                return LocalDate.parse(v, fmt);
            } catch (DateTimeParseException ignorada) {
                // prueba el siguiente formato
            }
        }
        e.add(new ErrorFila(f.numero(), col, "'" + v + "' no es una fecha válida (use AAAA-MM-DD o DD/MM/AAAA)"));
        return null;
    }
}
