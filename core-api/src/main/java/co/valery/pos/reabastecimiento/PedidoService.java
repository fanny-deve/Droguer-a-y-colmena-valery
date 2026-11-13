package co.valery.pos.reabastecimiento;

import co.valery.pos.auditoria.AuditService;
import co.valery.pos.common.ApiException;
import co.valery.pos.common.PageResponse;
import co.valery.pos.inventario.InventarioDtos.EntradaLoteRequest;
import co.valery.pos.inventario.InventarioService;
import co.valery.pos.reabastecimiento.PedidoDtos.PedidoDto;
import co.valery.pos.reabastecimiento.PedidoDtos.PedidoItemDto;
import co.valery.pos.reabastecimiento.PedidoDtos.PedidoItemRequest;
import co.valery.pos.reabastecimiento.PedidoDtos.PedidoRequest;
import co.valery.pos.reabastecimiento.PedidoDtos.PedidoResumenDto;
import co.valery.pos.reabastecimiento.PedidoDtos.RecepcionItem;
import co.valery.pos.reabastecimiento.PedidoDtos.RecepcionRequest;
import co.valery.pos.security.CurrentUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pedidos a proveedor (RF-15, RF-16). El sistema NUNCA compra por su cuenta: un pedido es un registro interno que la
 * administradora edita y "envía" por fuera del sistema; la recepción crea los lotes y los vincula al pedido y proveedor.
 */
@Service
public class PedidoService {
    private final JdbcClient jdbc;
    private final AuditService audit;
    private final InventarioService inventario;

    public PedidoService(JdbcClient jdbc, AuditService audit, InventarioService inventario) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.inventario = inventario;
    }

    // ---------- Creación y edición (solo en borrador) ----------

    @Transactional
    public PedidoDto crear(PedidoRequest r) {
        validarProveedor(r.proveedorId());
        UUID id = jdbc.sql("""
                insert into pedidos_proveedor(proveedor_id, fecha_esperada, notas, creado_por)
                values (:p, cast(:f as date), cast(:n as text), :u) returning id
                """).param("p", r.proveedorId()).param("f", r.fechaEsperada()).param("n", limpiar(r.notas()))
                .param("u", CurrentUser.require().id()).query((rs, i) -> rs.getObject(1, UUID.class)).single();
        insertarItems(id, r.items());
        PedidoDto dto = obtener(id);
        audit.registrar("CREAR_PEDIDO", "pedidos_proveedor", id.toString(),
                Map.of("numero", dto.numero(), "proveedor", dto.proveedor(), "referencias", dto.items().size(), "total", dto.totalEstimado()));
        return dto;
    }

    @Transactional
    public PedidoDto actualizar(UUID id, PedidoRequest r) {
        String estado = bloquear(id);
        if (!"BORRADOR".equals(estado)) {
            throw ApiException.conflict("PEDIDO_NO_EDITABLE", "Solo se pueden modificar pedidos en borrador");
        }
        validarProveedor(r.proveedorId());
        jdbc.sql("update pedidos_proveedor set proveedor_id = :p, fecha_esperada = cast(:f as date), notas = cast(:n as text) where id = :id")
                .param("p", r.proveedorId()).param("f", r.fechaEsperada()).param("n", limpiar(r.notas())).param("id", id).update();
        jdbc.sql("delete from pedido_items where pedido_id = :id").param("id", id).update();
        insertarItems(id, r.items());
        audit.registrar("ACTUALIZAR_PEDIDO", "pedidos_proveedor", id.toString(), Map.of("referencias", r.items().size()));
        return obtener(id);
    }

    private void insertarItems(UUID pedidoId, List<PedidoItemRequest> items) {
        Set<UUID> vistos = new HashSet<>();
        for (PedidoItemRequest it : items) {
            if (!vistos.add(it.productoId())) {
                throw ApiException.badRequest("PRODUCTO_REPETIDO", "Un producto solo puede aparecer una vez por pedido");
            }
            BigDecimal costoRef = jdbc.sql("select costo_referencia from productos where id = :id and activo").param("id", it.productoId())
                    .query(BigDecimal.class).optional()
                    .orElseThrow(() -> ApiException.badRequest("PRODUCTO_NO_VALIDO", "El producto " + it.productoId() + " no existe o está dado de baja"));
            jdbc.sql("""
                    insert into pedido_items(pedido_id, producto_id, cantidad_solicitada, costo_unitario_estimado, sugerido)
                    values (:ped, :p, :c, :costo, :s)
                    """).param("ped", pedidoId).param("p", it.productoId()).param("c", it.cantidad())
                    .param("costo", it.costoUnitarioEstimado() == null ? costoRef : it.costoUnitarioEstimado())
                    .param("s", Boolean.TRUE.equals(it.sugerido())).update();
        }
    }

    private void validarProveedor(UUID proveedorId) {
        Boolean activo = jdbc.sql("select activo from proveedores where id = :id and eliminado_en is null").param("id", proveedorId)
                .query((rs, i) -> rs.getBoolean(1)).optional()
                .orElseThrow(() -> ApiException.badRequest("PROVEEDOR_NO_ENCONTRADO", "El proveedor indicado no existe"));
        if (!activo) throw ApiException.unprocessable("PROVEEDOR_INACTIVO", "El proveedor indicado está inactivo");
    }

    private String bloquear(UUID id) {
        return jdbc.sql("select estado from pedidos_proveedor where id = :id for no key update").param("id", id)
                .query(String.class).optional()
                .orElseThrow(() -> ApiException.notFound("PEDIDO_NO_ENCONTRADO", "Pedido no encontrado"));
    }

    // ---------- Transiciones de estado ----------

    @Transactional
    public PedidoDto enviar(UUID id) {
        if (!"BORRADOR".equals(bloquear(id))) {
            throw ApiException.conflict("PEDIDO_NO_ENVIABLE", "Solo un pedido en borrador puede marcarse como enviado");
        }
        jdbc.sql("update pedidos_proveedor set estado = 'ENVIADO', enviado_en = now() where id = :id").param("id", id).update();
        audit.registrar("ENVIAR_PEDIDO", "pedidos_proveedor", id.toString(), Map.of());
        return obtener(id);
    }

    @Transactional
    public PedidoDto cancelar(UUID id, String motivo) {
        String estado = bloquear(id);
        if (!estado.equals("BORRADOR") && !estado.equals("ENVIADO")) {
            throw ApiException.conflict("PEDIDO_NO_CANCELABLE", "Solo se cancelan pedidos en borrador o enviados sin recepciones");
        }
        jdbc.sql("update pedidos_proveedor set estado = 'CANCELADO', cerrado_en = now(), notas = concat_ws(E'\\n', notas, cast(:m as text)) where id = :id")
                .param("m", "CANCELADO: " + motivo.trim()).param("id", id).update();
        audit.registrar("CANCELAR_PEDIDO", "pedidos_proveedor", id.toString(), Map.of("motivo", motivo.trim()));
        return obtener(id);
    }

    /** Cierra un pedido parcialmente recibido cuando el proveedor no entregará el resto. */
    @Transactional
    public PedidoDto cerrarParcial(UUID id, String motivo) {
        if (!"RECIBIDO_PARCIAL".equals(bloquear(id))) {
            throw ApiException.conflict("PEDIDO_NO_CERRABLE", "Solo un pedido parcialmente recibido puede cerrarse");
        }
        jdbc.sql("update pedidos_proveedor set estado = 'RECIBIDO', cerrado_en = now(), notas = concat_ws(E'\\n', notas, cast(:m as text)) where id = :id")
                .param("m", "CIERRE CON PENDIENTES: " + motivo.trim()).param("id", id).update();
        audit.registrar("CERRAR_PEDIDO", "pedidos_proveedor", id.toString(), Map.of("motivo", motivo.trim()));
        return obtener(id);
    }

    /** Recepción de mercancía: crea un lote por línea (con kardex y alertas) y lo vincula al pedido y al proveedor. */
    @Transactional
    public PedidoDto recibir(UUID id, RecepcionRequest r) {
        String estado = bloquear(id);
        if (!estado.equals("ENVIADO") && !estado.equals("RECIBIDO_PARCIAL")) {
            throw ApiException.conflict("PEDIDO_NO_RECEPCIONABLE", "Solo se recibe mercancía de pedidos enviados o parcialmente recibidos");
        }
        UUID proveedorId = jdbc.sql("select proveedor_id from pedidos_proveedor where id = :id").param("id", id)
                .query((rs, i) -> rs.getObject(1, UUID.class)).single();
        for (RecepcionItem it : r.items()) {
            int n = jdbc.sql("select count(*) from pedido_items where pedido_id = :p and producto_id = :prod").param("p", id)
                    .param("prod", it.productoId()).query(Integer.class).single();
            if (n == 0) {
                throw ApiException.unprocessable("PRODUCTO_FUERA_DE_PEDIDO", "El producto " + it.productoId() + " no pertenece a este pedido");
            }
            var lote = inventario.registrarEntrada(new EntradaLoteRequest(it.productoId(), proveedorId, it.codigoLote(),
                    it.fechaVencimiento(), it.cantidad(), it.costoUnitario(), it.notas()));
            jdbc.sql("update lotes set pedido_id = :p where id = :l").param("p", id).param("l", lote.id()).update();
            jdbc.sql("update pedido_items set cantidad_recibida = cantidad_recibida + :c where pedido_id = :p and producto_id = :prod")
                    .param("c", it.cantidad()).param("p", id).param("prod", it.productoId()).update();
        }
        long pendientes = jdbc.sql("select count(*) from pedido_items where pedido_id = :id and cantidad_recibida < cantidad_solicitada")
                .param("id", id).query(Long.class).single();
        if (pendientes == 0) {
            jdbc.sql("update pedidos_proveedor set estado = 'RECIBIDO', cerrado_en = now() where id = :id").param("id", id).update();
        } else {
            jdbc.sql("update pedidos_proveedor set estado = 'RECIBIDO_PARCIAL' where id = :id").param("id", id).update();
        }
        audit.registrar("RECIBIR_PEDIDO", "pedidos_proveedor", id.toString(),
                Map.of("lineas", r.items().size(), "pendientes", pendientes));
        return obtener(id);
    }

    // ---------- Consulta ----------

    @Transactional(readOnly = true)
    public PedidoDto obtener(UUID id) {
        var cab = jdbc.sql("""
                select p.id, p.numero, p.proveedor_id, v.nombre as proveedor, p.estado, p.fecha_esperada, p.notas, p.creado_por,
                       p.creado_en, p.enviado_en, p.cerrado_en
                from pedidos_proveedor p join proveedores v on v.id = p.proveedor_id where p.id = :id
                """).param("id", id).query((rs, i) -> new Object[]{rs.getObject("id", UUID.class), rs.getLong("numero"),
                        rs.getObject("proveedor_id", UUID.class), rs.getString("proveedor"), rs.getString("estado"),
                        rs.getObject("fecha_esperada", java.time.LocalDate.class), rs.getString("notas"),
                        rs.getObject("creado_por", UUID.class), rs.getObject("creado_en", OffsetDateTime.class),
                        rs.getObject("enviado_en", OffsetDateTime.class), rs.getObject("cerrado_en", OffsetDateTime.class)}).optional()
                .orElseThrow(() -> ApiException.notFound("PEDIDO_NO_ENCONTRADO", "Pedido no encontrado"));
        List<PedidoItemDto> items = jdbc.sql("""
                select pi.producto_id, p.codigo, p.nombre, pi.cantidad_solicitada, pi.cantidad_recibida, pi.costo_unitario_estimado, pi.sugerido
                from pedido_items pi join productos p on p.id = pi.producto_id where pi.pedido_id = :id order by p.nombre
                """).param("id", id)
                .query((rs, i) -> new PedidoItemDto(rs.getObject("producto_id", UUID.class), rs.getString("codigo"), rs.getString("nombre"),
                        rs.getBigDecimal("cantidad_solicitada"), rs.getBigDecimal("cantidad_recibida"),
                        rs.getBigDecimal("cantidad_solicitada").subtract(rs.getBigDecimal("cantidad_recibida")).max(BigDecimal.ZERO),
                        rs.getBigDecimal("costo_unitario_estimado"), rs.getBoolean("sugerido"))).list();
        BigDecimal total = items.stream().map(i -> i.cantidadSolicitada().multiply(i.costoUnitarioEstimado() == null ? BigDecimal.ZERO : i.costoUnitarioEstimado()))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        return new PedidoDto((UUID) cab[0], (Long) cab[1], (UUID) cab[2], (String) cab[3], (String) cab[4], (java.time.LocalDate) cab[5],
                (String) cab[6], (UUID) cab[7], (OffsetDateTime) cab[8], (OffsetDateTime) cab[9], (OffsetDateTime) cab[10], total, items);
    }

    @Transactional(readOnly = true)
    public PageResponse<PedidoResumenDto> listar(String estado, UUID proveedorId, int page, int size) {
        int tam = PageResponse.limitarTamano(size);
        int pag = Math.max(page, 0);
        String est = estado == null || estado.isBlank() ? null : estado.trim().toUpperCase(Locale.ROOT);
        String filtros = """
                where (cast(:e as text) is null or p.estado = cast(:e as text))
                  and (cast(:v as uuid) is null or p.proveedor_id = cast(:v as uuid))
                """;
        long total = jdbc.sql("select count(*) from pedidos_proveedor p " + filtros).param("e", est).param("v", proveedorId)
                .query(Long.class).single();
        List<PedidoResumenDto> items = jdbc.sql("""
                select p.id, p.numero, v.nombre as proveedor, p.estado, p.fecha_esperada, p.creado_en,
                       (select count(*) from pedido_items pi where pi.pedido_id = p.id) as referencias,
                       coalesce((select sum(pi.cantidad_solicitada * coalesce(pi.costo_unitario_estimado, 0)) from pedido_items pi where pi.pedido_id = p.id), 0) as total
                from pedidos_proveedor p join proveedores v on v.id = p.proveedor_id
                """ + filtros + " order by p.numero desc limit :l offset :o")
                .param("e", est).param("v", proveedorId).param("l", tam).param("o", pag * tam)
                .query((rs, i) -> new PedidoResumenDto(rs.getObject("id", UUID.class), rs.getLong("numero"), rs.getString("proveedor"),
                        rs.getString("estado"), rs.getObject("fecha_esperada", java.time.LocalDate.class),
                        rs.getObject("creado_en", OffsetDateTime.class), rs.getInt("referencias"),
                        rs.getBigDecimal("total").setScale(2, RoundingMode.HALF_UP))).list();
        return new PageResponse<>(items, pag, tam, total);
    }

    private static String limpiar(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
