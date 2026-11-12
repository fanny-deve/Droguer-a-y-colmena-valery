package co.valery.pos.catalogo;

import co.valery.pos.auditoria.AuditService;
import co.valery.pos.catalogo.CatalogoDtos.ProveedorDto;
import co.valery.pos.catalogo.CatalogoDtos.ProveedorProductoDto;
import co.valery.pos.catalogo.CatalogoDtos.ProveedorProductoRequest;
import co.valery.pos.catalogo.CatalogoDtos.ProveedorRequest;
import co.valery.pos.common.ApiException;
import co.valery.pos.common.PageResponse;
import co.valery.pos.security.CurrentUser;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProveedorService {
    private static final RowMapper<ProveedorDto> MAPPER = (rs, i) -> new ProveedorDto(rs.getObject("id", UUID.class),
            rs.getString("nombre"), rs.getString("nit"), rs.getString("contacto_nombre"), rs.getString("telefono"),
            rs.getString("email"), rs.getString("direccion"), rs.getInt("tiempo_entrega_dias"), rs.getString("notas"),
            rs.getBoolean("activo"));
    private static final String COLS = "id, nombre, nit, contacto_nombre, telefono, email, direccion, tiempo_entrega_dias, notas, activo";

    private final JdbcClient jdbc;
    private final AuditService audit;

    public ProveedorService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<ProveedorDto> listar(String q, Boolean activo, int page, int size) {
        int tam = PageResponse.limitarTamano(size);
        int pag = Math.max(page, 0);
        String filtros = """
                where eliminado_en is null
                  and (cast(:q as text) is null or nombre ilike '%' || cast(:q as text) || '%' or nit ilike cast(:q as text) || '%')
                  and (cast(:a as boolean) is null or activo = cast(:a as boolean))
                """;
        String qq = q == null || q.isBlank() ? null : q.trim();
        long total = jdbc.sql("select count(*) from proveedores " + filtros).param("q", qq).param("a", activo).query(Long.class).single();
        List<ProveedorDto> items = jdbc.sql("select " + COLS + " from proveedores " + filtros + " order by nombre limit :l offset :o")
                .param("q", qq).param("a", activo).param("l", tam).param("o", pag * tam).query(MAPPER).list();
        return new PageResponse<>(items, pag, tam, total);
    }

    @Transactional(readOnly = true)
    public ProveedorDto obtener(UUID id) {
        return jdbc.sql("select " + COLS + " from proveedores where id = :id and eliminado_en is null").param("id", id)
                .query(MAPPER).optional()
                .orElseThrow(() -> ApiException.notFound("PROVEEDOR_NO_ENCONTRADO", "Proveedor no encontrado"));
    }

    @Transactional
    public ProveedorDto crear(ProveedorRequest r) {
        if (jdbc.sql("select count(*) from proveedores where lower(nombre) = lower(:n) and eliminado_en is null")
                .param("n", r.nombre().trim()).query(Long.class).single() > 0) {
            throw ApiException.conflict("PROVEEDOR_DUPLICADO", "Ya existe un proveedor con ese nombre");
        }
        UUID id = jdbc.sql("""
                insert into proveedores(nombre, nit, contacto_nombre, telefono, email, direccion, tiempo_entrega_dias, notas, creado_por)
                values (:n, cast(:nit as text), cast(:c as text), cast(:t as text), cast(:e as text), cast(:d as text), :te,
                        cast(:no as text), :u) returning id
                """)
                .param("n", r.nombre().trim()).param("nit", limpiar(r.nit())).param("c", limpiar(r.contactoNombre()))
                .param("t", limpiar(r.telefono())).param("e", limpiarEmail(r.email())).param("d", limpiar(r.direccion()))
                .param("te", r.tiempoEntregaDias()).param("no", limpiar(r.notas())).param("u", CurrentUser.require().id())
                .query((rs, i) -> rs.getObject(1, UUID.class)).single();
        audit.registrar("CREAR_PROVEEDOR", "proveedores", id.toString(), Map.of("nombre", r.nombre().trim()));
        return obtener(id);
    }

    @Transactional
    public ProveedorDto actualizar(UUID id, ProveedorRequest r) {
        obtener(id);
        if (jdbc.sql("select count(*) from proveedores where lower(nombre) = lower(:n) and eliminado_en is null and id <> :id")
                .param("n", r.nombre().trim()).param("id", id).query(Long.class).single() > 0) {
            throw ApiException.conflict("PROVEEDOR_DUPLICADO", "Ya existe un proveedor con ese nombre");
        }
        jdbc.sql("""
                update proveedores set nombre = :n, nit = cast(:nit as text), contacto_nombre = cast(:c as text),
                       telefono = cast(:t as text), email = cast(:e as text), direccion = cast(:d as text),
                       tiempo_entrega_dias = :te, notas = cast(:no as text)
                where id = :id
                """)
                .param("n", r.nombre().trim()).param("nit", limpiar(r.nit())).param("c", limpiar(r.contactoNombre()))
                .param("t", limpiar(r.telefono())).param("e", limpiarEmail(r.email())).param("d", limpiar(r.direccion()))
                .param("te", r.tiempoEntregaDias()).param("no", limpiar(r.notas())).param("id", id).update();
        audit.registrar("ACTUALIZAR_PROVEEDOR", "proveedores", id.toString(), Map.of("nombre", r.nombre().trim()));
        return obtener(id);
    }

    @Transactional
    public ProveedorDto cambiarEstado(UUID id, boolean activo) {
        obtener(id);
        if (!activo) {
            long abiertos = jdbc.sql("select count(*) from pedidos_proveedor where proveedor_id = :id and estado in ('BORRADOR','ENVIADO','RECIBIDO_PARCIAL')")
                    .param("id", id).query(Long.class).single();
            if (abiertos > 0) {
                throw ApiException.conflict("PROVEEDOR_CON_PEDIDOS", "El proveedor tiene pedidos abiertos; ciérrelos o cancélelos primero");
            }
        }
        jdbc.sql("update proveedores set activo = :a where id = :id").param("a", activo).param("id", id).update();
        audit.registrar(activo ? "REACTIVAR_PROVEEDOR" : "DESACTIVAR_PROVEEDOR", "proveedores", id.toString(), Map.of());
        return obtener(id);
    }

    @Transactional(readOnly = true)
    public List<ProveedorProductoDto> productosDe(UUID proveedorId) {
        obtener(proveedorId);
        return jdbc.sql("""
                select p.id, p.codigo, p.nombre, pp.costo_ultimo from producto_proveedor pp
                join productos p on p.id = pp.producto_id where pp.proveedor_id = :id order by p.nombre
                """).param("id", proveedorId)
                .query((rs, i) -> new ProveedorProductoDto(rs.getObject("id", UUID.class), rs.getString("codigo"),
                        rs.getString("nombre"), rs.getBigDecimal("costo_ultimo"))).list();
    }

    /** Reemplaza la lista de productos que suministra el proveedor (RF-15). */
    @Transactional
    public List<ProveedorProductoDto> reemplazarProductos(UUID proveedorId, List<ProveedorProductoRequest> items) {
        obtener(proveedorId);
        jdbc.sql("delete from producto_proveedor where proveedor_id = :id").param("id", proveedorId).update();
        for (ProveedorProductoRequest it : items) {
            if (it == null || it.productoId() == null) {
                throw ApiException.badRequest("PRODUCTO_REQUERIDO", "Cada elemento debe indicar productoId");
            }
            if (jdbc.sql("select count(*) from productos where id = :p").param("p", it.productoId()).query(Long.class).single() == 0) {
                throw ApiException.badRequest("PRODUCTO_NO_ENCONTRADO", "Producto inexistente en la lista: " + it.productoId());
            }
            jdbc.sql("insert into producto_proveedor(producto_id, proveedor_id, costo_ultimo) values (:p, :v, :c) on conflict do nothing")
                    .param("p", it.productoId()).param("v", proveedorId).param("c", it.costoUltimo()).update();
        }
        audit.registrar("ACTUALIZAR_PRODUCTOS_PROVEEDOR", "proveedores", proveedorId.toString(), Map.of("cantidad", items.size()));
        return productosDe(proveedorId);
    }

    private static String limpiar(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String limpiarEmail(String s) {
        return s == null || s.isBlank() ? null : s.trim().toLowerCase(Locale.ROOT);
    }
}
