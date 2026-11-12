package co.valery.pos.catalogo;

import co.valery.pos.auditoria.AuditService;
import co.valery.pos.catalogo.CatalogoDtos.ProductoActualizarRequest;
import co.valery.pos.catalogo.CatalogoDtos.ProductoAdminDto;
import co.valery.pos.catalogo.CatalogoDtos.ProductoRequest;
import co.valery.pos.catalogo.CatalogoDtos.ProductoVentaDto;
import co.valery.pos.common.ApiException;
import co.valery.pos.common.PageResponse;
import co.valery.pos.inventario.AlertaService;
import co.valery.pos.security.CurrentUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductoService {
    private static final Set<String> UNIDADES =
            Set.of("UNIDAD", "CAJA", "BLISTER", "FRASCO", "GRAMO", "KILOGRAMO", "MILILITRO", "LITRO");

    record Fila(UUID id, String codigo, String nombre, String descripcion, int categoriaId, String categoria, String unidad,
                BigDecimal precioVenta, BigDecimal costoReferencia, boolean manejaVencimiento, BigDecimal stockMinimo,
                BigDecimal puntoReorden, UUID proveedorPrincipalId, String clasificacionAbc, boolean activo,
                BigDecimal stockTotal, BigDecimal stockVendible, BigDecimal stockVencido) {}

    private static final RowMapper<Fila> MAPPER = (rs, i) -> new Fila(rs.getObject("id", UUID.class), rs.getString("codigo"),
            rs.getString("nombre"), rs.getString("descripcion"), rs.getInt("categoria_id"), rs.getString("categoria"),
            rs.getString("unidad"), rs.getBigDecimal("precio_venta"), rs.getBigDecimal("costo_referencia"),
            rs.getBoolean("maneja_vencimiento"), rs.getBigDecimal("stock_minimo"), rs.getBigDecimal("punto_reorden"),
            rs.getObject("proveedor_principal_id", UUID.class), rs.getString("clasificacion_abc"), rs.getBoolean("activo"),
            rs.getBigDecimal("stock_total"), rs.getBigDecimal("stock_vendible"), rs.getBigDecimal("stock_vencido"));

    private static final String SELECT = """
            select p.id, p.codigo, p.nombre, p.descripcion, p.categoria_id, c.nombre as categoria, p.unidad, p.precio_venta,
                   p.costo_referencia, p.maneja_vencimiento, p.stock_minimo, p.punto_reorden, p.proveedor_principal_id,
                   p.clasificacion_abc, p.activo, s.stock_total, s.stock_vendible, s.stock_vencido
            from productos p
            join categorias c on c.id = p.categoria_id
            join v_stock_productos s on s.producto_id = p.id
            """;

    private static final String FILTROS = """
            where (cast(:q as text) is null or p.nombre ilike '%' || cast(:q as text) || '%' or p.codigo ilike cast(:q as text) || '%')
              and (cast(:cat as int) is null or p.categoria_id = cast(:cat as int))
              and (cast(:activo as boolean) is null or p.activo = cast(:activo as boolean))
              and (cast(:abc as text) is null or p.clasificacion_abc = cast(:abc as text))
              and (not :bajo or (p.punto_reorden > 0 and s.stock_vendible <= p.punto_reorden))
            """;

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final AlertaService alertas;

    public ProductoService(JdbcClient jdbc, AuditService audit, AlertaService alertas) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.alertas = alertas;
    }

    // ---------- Consulta ----------

    private PageResponse<Fila> buscarFilas(String q, Integer cat, Boolean activo, String abc, boolean bajo, int page, int size) {
        int tam = PageResponse.limitarTamano(size);
        int pag = Math.max(page, 0);
        String qq = q == null || q.isBlank() ? null : q.trim();
        String abcN = abc == null || abc.isBlank() ? null : abc.trim().toUpperCase(Locale.ROOT);
        long total = jdbc.sql("select count(*) from productos p join v_stock_productos s on s.producto_id = p.id " + FILTROS)
                .param("q", qq).param("cat", cat).param("activo", activo).param("abc", abcN).param("bajo", bajo)
                .query(Long.class).single();
        List<Fila> filas = jdbc.sql(SELECT + FILTROS + " order by p.nombre limit :l offset :o")
                .param("q", qq).param("cat", cat).param("activo", activo).param("abc", abcN).param("bajo", bajo)
                .param("l", tam).param("o", pag * tam).query(MAPPER).list();
        return new PageResponse<>(filas, pag, tam, total);
    }

    /** Búsqueda para punto de venta: solo productos activos y sin información de costos. */
    @Transactional(readOnly = true)
    public PageResponse<ProductoVentaDto> buscarParaVenta(String q, Integer categoriaId, int page, int size) {
        PageResponse<Fila> r = buscarFilas(q, categoriaId, true, null, false, page, size);
        return new PageResponse<>(r.items().stream().map(ProductoService::aVenta).toList(), r.page(), r.size(), r.total());
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductoAdminDto> buscarAdmin(String q, Integer categoriaId, Boolean activo, String abc,
                                                      boolean bajoStock, int page, int size) {
        PageResponse<Fila> r = buscarFilas(q, categoriaId, activo, abc, bajoStock, page, size);
        return new PageResponse<>(r.items().stream().map(ProductoService::aAdmin).toList(), r.page(), r.size(), r.total());
    }

    /** Consulta por código de barras o interno (lector de códigos, RF-02). */
    @Transactional(readOnly = true)
    public ProductoVentaDto porCodigoParaVenta(String codigo) {
        Fila f = jdbc.sql(SELECT + " where upper(p.codigo) = upper(:c) and p.activo").param("c", codigo.trim()).query(MAPPER).optional()
                .orElseThrow(() -> ApiException.notFound("PRODUCTO_NO_ENCONTRADO", "No existe un producto activo con ese código"));
        return aVenta(f);
    }

    @Transactional(readOnly = true)
    public ProductoVentaDto obtenerParaVenta(UUID id) {
        Fila f = fila(id);
        if (!f.activo()) throw ApiException.notFound("PRODUCTO_NO_ENCONTRADO", "Producto no encontrado");
        return aVenta(f);
    }

    @Transactional(readOnly = true)
    public ProductoAdminDto obtenerAdmin(UUID id) {
        return aAdmin(fila(id));
    }

    private Fila fila(UUID id) {
        return jdbc.sql(SELECT + " where p.id = :id").param("id", id).query(MAPPER).optional()
                .orElseThrow(() -> ApiException.notFound("PRODUCTO_NO_ENCONTRADO", "Producto no encontrado"));
    }

    // ---------- Escritura (solo administradora) ----------

    @Transactional
    public ProductoAdminDto crear(ProductoRequest r) {
        String codigo = r.codigo().trim();
        validarCodigo(codigo);
        String unidad = validarUnidad(r.unidad());
        validarCategoria(r.categoriaId());
        validarProveedor(r.proveedorPrincipalId());
        if (jdbc.sql("select count(*) from productos where upper(codigo) = upper(:c)").param("c", codigo).query(Long.class).single() > 0) {
            throw ApiException.conflict("CODIGO_DUPLICADO", "Ya existe un producto con ese código");
        }
        BigDecimal min = nz(r.stockMinimo());
        BigDecimal reorden = nz(r.puntoReorden());
        if (reorden.compareTo(min) < 0) {
            throw ApiException.unprocessable("PUNTO_REORDEN_INVALIDO", "El punto de reorden no puede ser menor al stock mínimo");
        }
        UUID id = jdbc.sql("""
                insert into productos(codigo, nombre, descripcion, categoria_id, unidad, precio_venta, costo_referencia,
                                      maneja_vencimiento, stock_minimo, punto_reorden, proveedor_principal_id, creado_por)
                values (:codigo, :nombre, cast(:desc as text), :cat, :unidad, :precio, :costo, :venc, :min, :reorden,
                        cast(:prov as uuid), :u) returning id
                """)
                .param("codigo", codigo).param("nombre", r.nombre().trim()).param("desc", limpiar(r.descripcion()))
                .param("cat", r.categoriaId()).param("unidad", unidad).param("precio", r.precioVenta())
                .param("costo", r.costoReferencia()).param("venc", r.manejaVencimiento() == null || r.manejaVencimiento())
                .param("min", min).param("reorden", reorden).param("prov", r.proveedorPrincipalId())
                .param("u", CurrentUser.require().id()).query((rs, i) -> rs.getObject(1, UUID.class)).single();
        Map<String, Object> det = new LinkedHashMap<>();
        det.put("codigo", codigo);
        det.put("nombre", r.nombre().trim());
        det.put("precioVenta", r.precioVenta());
        audit.registrar("CREAR_PRODUCTO", "productos", id.toString(), det);
        return obtenerAdmin(id);
    }

    @Transactional
    public ProductoAdminDto actualizar(UUID id, ProductoActualizarRequest r) {
        Fila antes = fila(id);
        validarUnidad(r.unidad());
        validarCategoria(r.categoriaId());
        validarProveedor(r.proveedorPrincipalId());
        BigDecimal min = nz(r.stockMinimo());
        BigDecimal reorden = nz(r.puntoReorden());
        if (reorden.compareTo(min) < 0) {
            throw ApiException.unprocessable("PUNTO_REORDEN_INVALIDO", "El punto de reorden no puede ser menor al stock mínimo");
        }
        jdbc.sql("""
                update productos set nombre = :nombre, descripcion = cast(:desc as text), categoria_id = :cat, unidad = :unidad,
                       precio_venta = :precio, costo_referencia = :costo, stock_minimo = :min, punto_reorden = :reorden,
                       proveedor_principal_id = cast(:prov as uuid)
                where id = :id
                """)
                .param("nombre", r.nombre().trim()).param("desc", limpiar(r.descripcion())).param("cat", r.categoriaId())
                .param("unidad", r.unidad().trim().toUpperCase(Locale.ROOT)).param("precio", r.precioVenta())
                .param("costo", r.costoReferencia()).param("min", min).param("reorden", reorden)
                .param("prov", r.proveedorPrincipalId()).param("id", id).update();
        Map<String, Object> det = new LinkedHashMap<>();
        det.put("codigo", antes.codigo());
        if (antes.precioVenta().compareTo(r.precioVenta()) != 0) {
            det.put("precioAntes", antes.precioVenta());
            det.put("precioDespues", r.precioVenta());
        }
        if (antes.costoReferencia().compareTo(r.costoReferencia()) != 0) {
            det.put("costoAntes", antes.costoReferencia());
            det.put("costoDespues", r.costoReferencia());
        }
        audit.registrar("ACTUALIZAR_PRODUCTO", "productos", id.toString(), det);
        alertas.evaluarProducto(id);
        return obtenerAdmin(id);
    }

    /** Baja lógica reversible (RF-06): el producto deja de venderse pero conserva su historial. */
    @Transactional
    public ProductoAdminDto cambiarEstado(UUID id, boolean activo) {
        Fila f = fila(id);
        if (f.activo() == activo) {
            throw ApiException.conflict(activo ? "YA_ACTIVO" : "YA_INACTIVO",
                    activo ? "El producto ya está activo" : "El producto ya está dado de baja");
        }
        jdbc.sql("update productos set activo = :a where id = :id").param("a", activo).param("id", id).update();
        audit.registrar(activo ? "REACTIVAR_PRODUCTO" : "DAR_DE_BAJA_PRODUCTO", "productos", id.toString(),
                Map.of("codigo", f.codigo(), "stockTotal", f.stockTotal()));
        return obtenerAdmin(id);
    }

    // ---------- Validaciones y mapeos ----------

    private static void validarCodigo(String codigo) {
        if (Ean13.esEan13(codigo) && !Ean13.valido(codigo)) {
            throw ApiException.badRequest("CODIGO_EAN_INVALIDO", "El código EAN-13 tiene un dígito verificador incorrecto");
        }
    }

    private static String validarUnidad(String unidad) {
        String u = unidad.trim().toUpperCase(Locale.ROOT);
        if (!UNIDADES.contains(u)) {
            throw ApiException.badRequest("UNIDAD_INVALIDA", "Unidad válida: " + String.join(", ", new java.util.TreeSet<>(UNIDADES)));
        }
        return u;
    }

    private void validarCategoria(int categoriaId) {
        Boolean activa = jdbc.sql("select activa from categorias where id = :id").param("id", categoriaId)
                .query((rs, i) -> rs.getBoolean(1)).optional()
                .orElseThrow(() -> ApiException.badRequest("CATEGORIA_NO_ENCONTRADA", "La categoría indicada no existe"));
        if (!activa) throw ApiException.unprocessable("CATEGORIA_INACTIVA", "La categoría indicada está inactiva");
    }

    private void validarProveedor(UUID proveedorId) {
        if (proveedorId == null) return;
        Boolean activo = jdbc.sql("select activo from proveedores where id = :id and eliminado_en is null").param("id", proveedorId)
                .query((rs, i) -> rs.getBoolean(1)).optional()
                .orElseThrow(() -> ApiException.badRequest("PROVEEDOR_NO_ENCONTRADO", "El proveedor indicado no existe"));
        if (!activo) throw ApiException.unprocessable("PROVEEDOR_INACTIVO", "El proveedor indicado está inactivo");
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String limpiar(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static ProductoVentaDto aVenta(Fila f) {
        return new ProductoVentaDto(f.id(), f.codigo(), f.nombre(), f.descripcion(), f.categoriaId(), f.categoria(), f.unidad(),
                f.precioVenta(), f.stockVendible(), f.manejaVencimiento(), f.activo());
    }

    private static ProductoAdminDto aAdmin(Fila f) {
        BigDecimal margen = f.precioVenta().signum() > 0
                ? f.precioVenta().subtract(f.costoReferencia()).multiply(BigDecimal.valueOf(100))
                        .divide(f.precioVenta(), 2, RoundingMode.HALF_UP)
                : null;
        return new ProductoAdminDto(f.id(), f.codigo(), f.nombre(), f.descripcion(), f.categoriaId(), f.categoria(), f.unidad(),
                f.precioVenta(), f.costoReferencia(), margen, f.manejaVencimiento(), f.stockMinimo(), f.puntoReorden(),
                f.proveedorPrincipalId(), f.clasificacionAbc(), f.stockTotal(), f.stockVendible(), f.stockVencido(), f.activo());
    }
}
