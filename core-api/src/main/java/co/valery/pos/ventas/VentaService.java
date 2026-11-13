package co.valery.pos.ventas;

import co.valery.pos.auditoria.AuditService;
import co.valery.pos.common.ApiException;
import co.valery.pos.common.PageResponse;
import co.valery.pos.config.ConfiguracionService;
import co.valery.pos.inventario.AlertaService;
import co.valery.pos.inventario.KardexService;
import co.valery.pos.security.AuthUser;
import co.valery.pos.security.CurrentUser;
import co.valery.pos.ventas.VentaDtos.AnularRequest;
import co.valery.pos.ventas.VentaDtos.ComprobanteDto;
import co.valery.pos.ventas.VentaDtos.ComprobanteItem;
import co.valery.pos.ventas.VentaDtos.ItemRequest;
import co.valery.pos.ventas.VentaDtos.VentaDto;
import co.valery.pos.ventas.VentaDtos.VentaItemDto;
import co.valery.pos.ventas.VentaDtos.VentaRequest;
import co.valery.pos.ventas.VentaDtos.VentaResumenDto;
import co.valery.pos.ventas.VentaDtos.VerificacionSello;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registro de ventas (RF-01, RF-03, RF-04, RF-05): descuento FEFO atómico por lote, precio fijado por el servidor,
 * idempotencia por clave del cliente, sello de integridad con hora del servidor y kardex de cada movimiento.
 */
@Service
public class VentaService {
    private static final Set<String> FORMAS = Set.of("EFECTIVO", "TARJETA", "TRANSFERENCIA", "BILLETERA_DIGITAL");
    private static final Set<String> UNIDADES_ENTERAS = Set.of("UNIDAD", "CAJA", "BLISTER", "FRASCO");
    private static final DateTimeFormatter FECHA_LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private record ProductoVenta(UUID id, String codigo, String nombre, boolean activo, BigDecimal precio, int categoriaId, String unidad) {}

    private record Lote(UUID id, String codigo, BigDecimal disponible, BigDecimal costo) {}

    private record Linea(int numero, ProductoVenta producto, Lote lote, BigDecimal cantidad, BigDecimal subtotal) {}

    public record Resultado(VentaDto venta, boolean duplicada) {}

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final KardexService kardex;
    private final AlertaService alertas;
    private final ConfiguracionService config;

    public VentaService(JdbcClient jdbc, AuditService audit, KardexService kardex, AlertaService alertas, ConfiguracionService config) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.kardex = kardex;
        this.alertas = alertas;
        this.config = config;
    }

    // =====================================================================================================
    // Registro de la venta
    // =====================================================================================================

    @Transactional
    public Resultado registrar(VentaRequest r, String origen) {
        AuthUser actor = CurrentUser.require();

        // 1) Idempotencia: la misma clave nunca genera dos ventas
        Optional<UUID> existente = jdbc.sql("select id from ventas where idempotency_key = :k").param("k", r.idempotencyKey())
                .query((rs, i) -> rs.getObject(1, UUID.class)).optional();
        if (existente.isPresent()) {
            UUID cajeroDeLaVenta = jdbc.sql("select cajero_id from ventas where id = :id").param("id", existente.get())
                    .query((rs, i) -> rs.getObject(1, UUID.class)).single();
            if (!cajeroDeLaVenta.equals(actor.id()) && !actor.esAdmin()) {
                throw ApiException.conflict("IDEMPOTENCY_KEY_EN_USO", "La clave de la venta ya fue usada por otra cuenta");
            }
            return new Resultado(detalle(existente.get(), actor, true), true);
        }

        // 2) Validaciones de pago
        String forma = r.formaPago().trim().toUpperCase(Locale.ROOT);
        if (!FORMAS.contains(forma)) {
            throw ApiException.badRequest("FORMA_PAGO_INVALIDA", "Forma de pago válida: " + String.join(", ", new java.util.TreeSet<>(FORMAS)));
        }
        BigDecimal descuento = r.descuento() == null ? BigDecimal.ZERO : r.descuento().setScale(2, RoundingMode.HALF_UP);
        if (descuento.signum() > 0 && !actor.esAdmin()) {
            audit.denegado(actor, "DESCUENTO_DENEGADO", "ventas", r.idempotencyKey().toString(), Map.of("descuento", descuento));
            throw ApiException.forbidden("DESCUENTO_NO_PERMITIDO", "Solo la administradora puede aplicar descuentos");
        }

        // 3) Sesión de caja
        UUID sesionId = null;
        if ("ONLINE".equals(origen)) {
            sesionId = jdbc.sql("select id from sesiones_caja where cajero_id = :c and estado = 'ABIERTA' for share").param("c", actor.id())
                    .query((rs, i) -> rs.getObject(1, UUID.class)).optional().orElse(null);
            boolean exigir = Boolean.parseBoolean(config.getString("ventas.exigir_caja_abierta", "true"));
            if (sesionId == null && exigir && !actor.esAdmin()) {
                throw ApiException.conflict("CAJA_CERRADA", "Debe abrir su caja antes de registrar ventas");
            }
        } else if (r.fechaCliente() != null) {
            sesionId = jdbc.sql("""
                    select id from sesiones_caja where cajero_id = :c and abierta_en <= :f and (cerrada_en is null or :f <= cerrada_en)
                    order by abierta_en desc limit 1
                    """).param("c", actor.id()).param("f", r.fechaCliente())
                    .query((rs, i) -> rs.getObject(1, UUID.class)).optional().orElse(null);
        }

        // 4) Asignación FEFO por lote (bloquea las filas en orden determinista para evitar bloqueos mutuos)
        Map<UUID, BigDecimal> pedido = new TreeMap<>();
        for (ItemRequest it : r.items()) pedido.merge(it.productoId(), it.cantidad(), BigDecimal::add);
        LocalDate hoy = LocalDate.now(AlertaService.ZONA);
        List<Linea> lineas = new ArrayList<>();
        int numeroLinea = 0;
        for (Map.Entry<UUID, BigDecimal> e : pedido.entrySet()) {
            ProductoVenta p = jdbc.sql("select id, codigo, nombre, activo, precio_venta, categoria_id, unidad from productos where id = :id")
                    .param("id", e.getKey())
                    .query((rs, i) -> new ProductoVenta(rs.getObject("id", UUID.class), rs.getString("codigo"), rs.getString("nombre"),
                            rs.getBoolean("activo"), rs.getBigDecimal("precio_venta"), rs.getInt("categoria_id"), rs.getString("unidad")))
                    .optional().orElseThrow(() -> ApiException.notFound("PRODUCTO_NO_ENCONTRADO", "El producto " + e.getKey() + " no existe"));
            if (!p.activo()) {
                throw ApiException.unprocessable("PRODUCTO_INACTIVO", "El producto '" + p.nombre() + "' está dado de baja y no puede venderse");
            }
            BigDecimal cantidad = e.getValue();
            if (UNIDADES_ENTERAS.contains(p.unidad()) && cantidad.stripTrailingZeros().scale() > 0) {
                throw ApiException.unprocessable("CANTIDAD_INVALIDA", "'" + p.nombre() + "' se vende en unidades enteras");
            }
            List<Lote> lotes = jdbc.sql("""
                    select id, codigo_lote, cantidad_disponible, costo_unitario from lotes
                    where producto_id = :p and estado = 'ACTIVO' and cantidad_disponible > 0
                      and (fecha_vencimiento is null or fecha_vencimiento > :hoy)
                    order by fecha_vencimiento nulls last, fecha_ingreso, id
                    for no key update
                    """).param("p", p.id()).param("hoy", hoy)
                    .query((rs, i) -> new Lote(rs.getObject("id", UUID.class), rs.getString("codigo_lote"),
                            rs.getBigDecimal("cantidad_disponible"), rs.getBigDecimal("costo_unitario"))).list();
            BigDecimal faltante = cantidad;
            for (Lote l : lotes) {
                if (faltante.signum() == 0) break;
                BigDecimal tomar = faltante.min(l.disponible());
                lineas.add(new Linea(++numeroLinea, p, l, tomar, p.precio().multiply(tomar).setScale(2, RoundingMode.HALF_UP)));
                faltante = faltante.subtract(tomar);
            }
            if (faltante.signum() > 0) {
                BigDecimal disponible = cantidad.subtract(faltante);
                throw ApiException.unprocessable("STOCK_INSUFICIENTE", "Stock insuficiente de '" + p.nombre() + "': disponible "
                        + disponible.stripTrailingZeros().toPlainString() + ", solicitado " + cantidad.stripTrailingZeros().toPlainString());
            }
        }

        // 5) Totales y pago
        BigDecimal subtotal = lineas.stream().map(Linea::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        if (descuento.compareTo(subtotal) > 0) {
            throw ApiException.unprocessable("DESCUENTO_INVALIDO", "El descuento no puede superar el subtotal");
        }
        BigDecimal total = subtotal.subtract(descuento);
        BigDecimal recibido = null;
        BigDecimal cambio = null;
        if (forma.equals("EFECTIVO")) {
            if (r.montoRecibido() == null) {
                throw ApiException.badRequest("MONTO_RECIBIDO_REQUERIDO", "En pagos en efectivo debe indicar el monto recibido");
            }
            if (r.montoRecibido().compareTo(total) < 0) {
                throw ApiException.unprocessable("MONTO_INSUFICIENTE", "El monto recibido es menor al total de la venta (" + total.toPlainString() + ")");
            }
            recibido = r.montoRecibido().setScale(2, RoundingMode.HALF_UP);
            cambio = recibido.subtract(total);
        }

        // 6) Venta sellada con la hora del servidor de base de datos
        UUID ventaId = UUID.randomUUID();
        OffsetDateTime ahora = jdbc.sql("select now()").query((rs, i) -> rs.getObject(1, OffsetDateTime.class)).single();
        String sello = VentaSello.calcular(ventaId, r.idempotencyKey(), actor.id(), ahora, forma, total,
                lineas.stream().map(l -> new VentaSello.Linea(l.producto().id(), l.lote().id(), l.cantidad(), l.producto().precio(), l.subtotal())).toList());
        long numero = jdbc.sql("""
                insert into ventas(id, cajero_id, sesion_caja_id, forma_pago, subtotal, descuento, total, monto_recibido, cambio,
                                   idempotency_key, origen, fecha_cliente, fecha_servidor, hash_sellado)
                values (:id, :cajero, cast(:sesion as uuid), :forma, :sub, :desc, :total, cast(:rec as numeric), cast(:cam as numeric),
                        :key, :origen, cast(:fc as timestamptz), :fs, :hash) returning numero
                """)
                .param("id", ventaId).param("cajero", actor.id()).param("sesion", sesionId).param("forma", forma)
                .param("sub", subtotal).param("desc", descuento).param("total", total).param("rec", recibido).param("cam", cambio)
                .param("key", r.idempotencyKey()).param("origen", origen).param("fc", r.fechaCliente()).param("fs", ahora)
                .param("hash", sello).query((rs, i) -> rs.getLong(1)).single();

        Set<UUID> productos = new LinkedHashSet<>();
        for (Linea l : lineas) {
            jdbc.sql("""
                    insert into venta_items(venta_id, linea, producto_id, lote_id, categoria_id, cantidad, precio_unitario, costo_unitario, subtotal)
                    values (:v, :n, :p, :l, :c, :q, :precio, :costo, :sub)
                    """).param("v", ventaId).param("n", l.numero()).param("p", l.producto().id()).param("l", l.lote().id())
                    .param("c", l.producto().categoriaId()).param("q", l.cantidad()).param("precio", l.producto().precio())
                    .param("costo", l.lote().costo()).param("sub", l.subtotal()).update();
            BigDecimal despues = l.lote().disponible().subtract(l.cantidad());
            jdbc.sql("update lotes set cantidad_disponible = :n where id = :id").param("n", despues).param("id", l.lote().id()).update();
            kardex.registrar(l.lote().id(), l.producto().id(), "VENTA", l.cantidad().negate(), l.lote().disponible(), despues,
                    l.lote().costo(), "VENTA", ventaId.toString(), null, actor.id());
            productos.add(l.producto().id());
        }
        Map<String, Object> det = new LinkedHashMap<>();
        det.put("numero", numero);
        det.put("total", total);
        det.put("formaPago", forma);
        det.put("origen", origen);
        det.put("articulos", productos.size());
        audit.registrar("CREAR_VENTA", "ventas", ventaId.toString(), det);
        for (UUID p : productos) alertas.evaluarProducto(p);
        return new Resultado(detalle(ventaId, actor, false), false);
    }

    // =====================================================================================================
    // Consulta
    // =====================================================================================================

    private record Cab(UUID id, long numero, UUID cajeroId, String cajero, String forma, BigDecimal subtotal, BigDecimal descuento,
                       BigDecimal total, BigDecimal recibido, BigDecimal cambio, String estado, String origen,
                       OffsetDateTime fechaServidor, OffsetDateTime fechaCliente, String hash, OffsetDateTime anuladaEn, String motivo) {}

    private static String numeroComprobante(long numero) {
        return "V-" + String.format("%08d", numero);
    }

    /** La cajera solo ve sus propias ventas; la administradora ve todas. Para lo demás responde 404 (no revela existencia). */
    private Cab cabecera(UUID id, AuthUser actor) {
        Cab c = jdbc.sql("""
                select v.id, v.numero, v.cajero_id, u.nombre_completo as cajero, v.forma_pago, v.subtotal, v.descuento, v.total,
                       v.monto_recibido, v.cambio, v.estado, v.origen, v.fecha_servidor, v.fecha_cliente, v.hash_sellado,
                       v.anulada_en, v.motivo_anulacion
                from ventas v join usuarios u on u.id = v.cajero_id where v.id = :id
                """).param("id", id)
                .query((rs, i) -> new Cab(rs.getObject("id", UUID.class), rs.getLong("numero"), rs.getObject("cajero_id", UUID.class),
                        rs.getString("cajero"), rs.getString("forma_pago"), rs.getBigDecimal("subtotal"), rs.getBigDecimal("descuento"),
                        rs.getBigDecimal("total"), rs.getBigDecimal("monto_recibido"), rs.getBigDecimal("cambio"), rs.getString("estado"),
                        rs.getString("origen"), rs.getObject("fecha_servidor", OffsetDateTime.class),
                        rs.getObject("fecha_cliente", OffsetDateTime.class), rs.getString("hash_sellado"),
                        rs.getObject("anulada_en", OffsetDateTime.class), rs.getString("motivo_anulacion")))
                .optional().orElseThrow(() -> ApiException.notFound("VENTA_NO_ENCONTRADA", "Venta no encontrada"));
        if (!actor.esAdmin() && !c.cajeroId().equals(actor.id())) {
            throw ApiException.notFound("VENTA_NO_ENCONTRADA", "Venta no encontrada");
        }
        return c;
    }

    @Transactional(readOnly = true)
    public VentaDto obtener(UUID id) {
        return detalle(id, CurrentUser.require(), false);
    }

    private VentaDto detalle(UUID id, AuthUser actor, boolean duplicada) {
        Cab c = cabecera(id, actor);
        List<VentaItemDto> items = jdbc.sql("""
                select vi.linea, vi.producto_id, p.codigo, p.nombre, vi.lote_id, l.codigo_lote, vi.cantidad, vi.precio_unitario,
                       vi.subtotal, vi.costo_unitario
                from venta_items vi join productos p on p.id = vi.producto_id join lotes l on l.id = vi.lote_id
                where vi.venta_id = :id order by vi.linea
                """).param("id", id)
                .query((rs, i) -> new VentaItemDto(rs.getInt("linea"), rs.getObject("producto_id", UUID.class), rs.getString("codigo"),
                        rs.getString("nombre"), rs.getObject("lote_id", UUID.class), rs.getString("codigo_lote"),
                        rs.getBigDecimal("cantidad"), rs.getBigDecimal("precio_unitario"), rs.getBigDecimal("subtotal"),
                        actor.esAdmin() ? rs.getBigDecimal("costo_unitario") : null)).list();
        return new VentaDto(c.id(), c.numero(), numeroComprobante(c.numero()), c.cajeroId(), c.cajero(), c.forma(), c.subtotal(),
                c.descuento(), c.total(), c.recibido(), c.cambio(), c.estado(), c.origen(), c.fechaServidor(), c.fechaCliente(),
                c.hash(), duplicada, items, c.anuladaEn(), c.motivo());
    }

    @Transactional(readOnly = true)
    public PageResponse<VentaResumenDto> listar(UUID cajeroId, String estado, String forma, LocalDate desde, LocalDate hasta,
                                                int page, int size) {
        AuthUser actor = CurrentUser.require();
        UUID filtroCajero = actor.esAdmin() ? cajeroId : actor.id();
        int tam = PageResponse.limitarTamano(size);
        int pag = Math.max(page, 0);
        String est = estado == null || estado.isBlank() ? null : estado.trim().toUpperCase(Locale.ROOT);
        String fp = forma == null || forma.isBlank() ? null : forma.trim().toUpperCase(Locale.ROOT);
        String filtros = """
                where (cast(:c as uuid) is null or v.cajero_id = cast(:c as uuid))
                  and (cast(:e as text) is null or v.estado = cast(:e as text))
                  and (cast(:f as text) is null or v.forma_pago = cast(:f as text))
                  and (cast(:d as date) is null or v.fecha_servidor >= (cast(:d as date)::timestamp at time zone 'America/Bogota'))
                  and (cast(:h as date) is null or v.fecha_servidor < ((cast(:h as date) + 1)::timestamp at time zone 'America/Bogota'))
                """;
        long total = jdbc.sql("select count(*) from ventas v " + filtros).param("c", filtroCajero).param("e", est).param("f", fp)
                .param("d", desde).param("h", hasta).query(Long.class).single();
        List<VentaResumenDto> items = jdbc.sql("""
                select v.id, v.numero, v.cajero_id, u.nombre_completo as cajero, v.forma_pago, v.total, v.estado, v.origen,
                       v.fecha_servidor, (select count(distinct vi.producto_id) from venta_items vi where vi.venta_id = v.id) as articulos
                from ventas v join usuarios u on u.id = v.cajero_id
                """ + filtros + " order by v.numero desc limit :l offset :o")
                .param("c", filtroCajero).param("e", est).param("f", fp).param("d", desde).param("h", hasta)
                .param("l", tam).param("o", pag * tam)
                .query((rs, i) -> new VentaResumenDto(rs.getObject("id", UUID.class), rs.getLong("numero"), numeroComprobante(rs.getLong("numero")),
                        rs.getObject("cajero_id", UUID.class), rs.getString("cajero"), rs.getString("forma_pago"), rs.getBigDecimal("total"),
                        rs.getString("estado"), rs.getString("origen"), rs.getObject("fecha_servidor", OffsetDateTime.class),
                        rs.getInt("articulos"))).list();
        return new PageResponse<>(items, pag, tam, total);
    }

    /** Comprobante interno de venta (RF-04). No es factura electrónica: la facturación DIAN está fuera del alcance. */
    @Transactional(readOnly = true)
    public ComprobanteDto comprobante(UUID id) {
        AuthUser actor = CurrentUser.require();
        Cab c = cabecera(id, actor);
        Map<UUID, ComprobanteItem> agrupados = new LinkedHashMap<>();
        jdbc.sql("""
                select vi.producto_id, p.nombre, vi.cantidad, vi.precio_unitario, vi.subtotal
                from venta_items vi join productos p on p.id = vi.producto_id where vi.venta_id = :id order by vi.linea
                """).param("id", id)
                .query((rs, i) -> {
                    UUID pid = rs.getObject("producto_id", UUID.class);
                    ComprobanteItem previo = agrupados.get(pid);
                    BigDecimal cant = rs.getBigDecimal("cantidad");
                    BigDecimal sub = rs.getBigDecimal("subtotal");
                    agrupados.put(pid, previo == null
                            ? new ComprobanteItem(rs.getString("nombre"), cant, rs.getBigDecimal("precio_unitario"), sub)
                            : new ComprobanteItem(previo.descripcion(), previo.cantidad().add(cant), previo.precioUnitario(), previo.subtotal().add(sub)));
                    return 0;
                }).list();
        String fecha = c.fechaServidor().atZoneSameInstant(AlertaService.ZONA).format(FECHA_LOCAL);
        return new ComprobanteDto("Droguería & Colmena Valery", "COMPROBANTE DE VENTA", numeroComprobante(c.numero()), fecha, c.cajero(),
                new ArrayList<>(agrupados.values()), c.subtotal(), c.descuento(), c.total(), c.forma(), c.recibido(), c.cambio(),
                c.estado(), c.hash().substring(0, 16).toUpperCase(Locale.ROOT),
                "Documento interno de control. No constituye factura electrónica de venta.");
    }

    // =====================================================================================================
    // Anulación (solo administradora) y verificación del sello
    // =====================================================================================================

    /** Anula la venta con motivo: repone cada lote, deja el movimiento en el kardex y conserva el registro original (RF-05). */
    @Transactional
    public VentaDto anular(UUID id, AnularRequest r) {
        AuthUser admin = CurrentUser.require();
        var v = jdbc.sql("select estado, numero, total from ventas where id = :id for no key update").param("id", id)
                .query((rs, i) -> new Object[]{rs.getString("estado"), rs.getLong("numero"), rs.getBigDecimal("total")}).optional()
                .orElseThrow(() -> ApiException.notFound("VENTA_NO_ENCONTRADA", "Venta no encontrada"));
        if ("ANULADA".equals(v[0])) {
            throw ApiException.conflict("VENTA_YA_ANULADA", "La venta ya fue anulada");
        }
        record Item(UUID loteId, UUID productoId, BigDecimal cantidad, BigDecimal costo) {}
        List<Item> items = jdbc.sql("select lote_id, producto_id, cantidad, costo_unitario from venta_items where venta_id = :id order by lote_id, linea")
                .param("id", id).query((rs, i) -> new Item(rs.getObject("lote_id", UUID.class), rs.getObject("producto_id", UUID.class),
                        rs.getBigDecimal("cantidad"), rs.getBigDecimal("costo_unitario"))).list();
        Set<UUID> productos = new LinkedHashSet<>();
        for (Item it : items) {
            BigDecimal antes = jdbc.sql("select cantidad_disponible from lotes where id = :l for no key update").param("l", it.loteId())
                    .query((rs, i) -> rs.getBigDecimal(1)).single();
            BigDecimal despues = antes.add(it.cantidad());
            jdbc.sql("update lotes set cantidad_disponible = :n where id = :l").param("n", despues).param("l", it.loteId()).update();
            kardex.registrar(it.loteId(), it.productoId(), "ANULACION_VENTA", it.cantidad(), antes, despues, it.costo(), "VENTA",
                    id.toString(), r.motivo().trim(), admin.id());
            productos.add(it.productoId());
        }
        jdbc.sql("update ventas set estado = 'ANULADA', anulada_en = now(), anulada_por = :a, motivo_anulacion = :m where id = :id")
                .param("a", admin.id()).param("m", r.motivo().trim()).param("id", id).update();
        Map<String, Object> det = new LinkedHashMap<>();
        det.put("numero", v[1]);
        det.put("total", v[2]);
        det.put("motivo", r.motivo().trim());
        audit.registrar("ANULAR_VENTA", "ventas", id.toString(), det);
        for (UUID p : productos) alertas.evaluarProducto(p);
        return detalle(id, admin, false);
    }

    /** Recalcula el sello desde los datos almacenados y lo compara con el guardado al momento de la venta. */
    @Transactional(readOnly = true)
    public VerificacionSello verificarSello(UUID id) {
        var v = jdbc.sql("""
                select numero, cajero_id, forma_pago, total, idempotency_key, fecha_servidor, hash_sellado from ventas where id = :id
                """).param("id", id)
                .query((rs, i) -> new Object[]{rs.getLong("numero"), rs.getObject("cajero_id", UUID.class), rs.getString("forma_pago"),
                        rs.getBigDecimal("total"), rs.getObject("idempotency_key", UUID.class),
                        rs.getObject("fecha_servidor", OffsetDateTime.class), rs.getString("hash_sellado")}).optional()
                .orElseThrow(() -> ApiException.notFound("VENTA_NO_ENCONTRADA", "Venta no encontrada"));
        List<VentaSello.Linea> lineas = jdbc.sql("select producto_id, lote_id, cantidad, precio_unitario, subtotal from venta_items where venta_id = :id order by linea")
                .param("id", id).query((rs, i) -> new VentaSello.Linea(rs.getObject("producto_id", UUID.class), rs.getObject("lote_id", UUID.class),
                        rs.getBigDecimal("cantidad"), rs.getBigDecimal("precio_unitario"), rs.getBigDecimal("subtotal"))).list();
        String calculado = VentaSello.calcular(id, (UUID) v[4], (UUID) v[1], (OffsetDateTime) v[5], (String) v[2], (BigDecimal) v[3], lineas);
        return new VerificacionSello(id, (Long) v[0], calculado.equals(v[6]), (String) v[6], calculado);
    }
}
