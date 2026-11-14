package co.valery.pos.inventario;

import co.valery.pos.auditoria.AuditService;
import co.valery.pos.common.ApiException;
import co.valery.pos.common.PageResponse;
import co.valery.pos.inventario.InventarioDtos.AjusteDto;
import co.valery.pos.inventario.InventarioDtos.AjusteRequest;
import co.valery.pos.inventario.InventarioDtos.EntradaLoteRequest;
import co.valery.pos.inventario.InventarioDtos.LoteDto;
import co.valery.pos.inventario.InventarioDtos.MovimientoDto;
import co.valery.pos.security.AuthUser;
import co.valery.pos.security.CurrentUser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
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

/** Entradas por lote, mermas/ajustes, bloqueo de lotes y consulta del kardex (RF-07, RF-08, RF-09, RF-10, RF-13). */
@Service
public class InventarioService {
    private static final Set<String> TIPOS_AJUSTE = Set.of("VENCIMIENTO", "DANO", "DIFERENCIA_CONTEO", "OTRO");

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final KardexService kardex;
    private final AlertaService alertas;

    public InventarioService(JdbcClient jdbc, AuditService audit, KardexService kardex, AlertaService alertas) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.kardex = kardex;
        this.alertas = alertas;
    }

    // ---------- Entradas ----------

    @Transactional
    public LoteDto registrarEntrada(EntradaLoteRequest r) {
        AuthUser actor = CurrentUser.require();
        var prod = jdbc.sql("select nombre, codigo, activo, maneja_vencimiento from productos where id = :id").param("id", r.productoId())
                .query((rs, i) -> Map.<String, Object>of("nombre", rs.getString("nombre"), "codigo", rs.getString("codigo"),
                        "activo", rs.getBoolean("activo"), "venc", rs.getBoolean("maneja_vencimiento"))).optional()
                .orElseThrow(() -> ApiException.notFound("PRODUCTO_NO_ENCONTRADO", "Producto no encontrado"));
        if (!(Boolean) prod.get("activo")) {
            throw ApiException.unprocessable("PRODUCTO_INACTIVO", "No se puede ingresar inventario a un producto dado de baja");
        }
        if (r.proveedorId() != null) {
            Boolean activo = jdbc.sql("select activo from proveedores where id = :id and eliminado_en is null").param("id", r.proveedorId())
                    .query((rs, i) -> rs.getBoolean(1)).optional()
                    .orElseThrow(() -> ApiException.badRequest("PROVEEDOR_NO_ENCONTRADO", "El proveedor indicado no existe"));
            if (!activo) throw ApiException.unprocessable("PROVEEDOR_INACTIVO", "El proveedor indicado está inactivo");
        }
        LocalDate hoy = LocalDate.now(AlertaService.ZONA);
        if ((Boolean) prod.get("venc") && r.fechaVencimiento() == null) {
            throw ApiException.badRequest("FECHA_VENCIMIENTO_REQUERIDA", "Este producto exige fecha de vencimiento en cada lote");
        }
        if (r.fechaVencimiento() != null && !r.fechaVencimiento().isAfter(hoy)) {
            throw ApiException.unprocessable("LOTE_VENCIDO", "No se puede ingresar un lote ya vencido o que vence hoy");
        }
        String codigoLote = r.codigoLote().trim();
        if (jdbc.sql("select count(*) from lotes where producto_id = :p and upper(codigo_lote) = upper(:c)")
                .param("p", r.productoId()).param("c", codigoLote).query(Long.class).single() > 0) {
            throw ApiException.conflict("LOTE_DUPLICADO", "Ya existe un lote con ese código para este producto");
        }
        UUID loteId = jdbc.sql("""
                insert into lotes(producto_id, proveedor_id, codigo_lote, fecha_vencimiento, cantidad_inicial,
                                  cantidad_disponible, costo_unitario, notas, creado_por)
                values (:p, cast(:v as uuid), :c, cast(:f as date), :q, :q, :costo, cast(:n as text), :u) returning id
                """)
                .param("p", r.productoId()).param("v", r.proveedorId()).param("c", codigoLote).param("f", r.fechaVencimiento())
                .param("q", r.cantidad()).param("costo", r.costoUnitario())
                .param("n", r.notas() == null || r.notas().isBlank() ? null : r.notas().trim()).param("u", actor.id())
                .query((rs, i) -> rs.getObject(1, UUID.class)).single();
        long movId = kardex.registrar(loteId, r.productoId(), "ENTRADA", r.cantidad(), BigDecimal.ZERO, r.cantidad(),
                r.costoUnitario(), "LOTE", loteId.toString(), "Entrada de mercancía", actor.id());
        jdbc.sql("update productos set costo_referencia = :c where id = :id").param("c", r.costoUnitario()).param("id", r.productoId()).update();
        if (r.proveedorId() != null) {
            jdbc.sql("""
                    insert into producto_proveedor(producto_id, proveedor_id, costo_ultimo) values (:p, :v, :c)
                    on conflict (producto_id, proveedor_id) do update set costo_ultimo = excluded.costo_ultimo
                    """).param("p", r.productoId()).param("v", r.proveedorId()).param("c", r.costoUnitario()).update();
        }
        Map<String, Object> det = new LinkedHashMap<>();
        det.put("producto", prod.get("codigo"));
        det.put("lote", codigoLote);
        det.put("cantidad", r.cantidad());
        det.put("costoUnitario", r.costoUnitario());
        det.put("vence", r.fechaVencimiento() == null ? null : r.fechaVencimiento().toString());
        det.put("movimientoId", movId);
        audit.registrar("CREAR_LOTE", "lotes", loteId.toString(), det);
        alertas.evaluarProducto(r.productoId());
        return obtenerLote(loteId);
    }

    /** Carga inicial desde importación: admite lotes ya vencidos (existencias físicas reales que luego se dan de baja). */
    @Transactional
    public UUID registrarCargaInicial(UUID productoId, String codigoLote, LocalDate vence, BigDecimal cantidad, BigDecimal costo) {
        AuthUser actor = CurrentUser.require();
        UUID loteId = jdbc.sql("""
                insert into lotes(producto_id, codigo_lote, fecha_vencimiento, cantidad_inicial, cantidad_disponible, costo_unitario, notas, creado_por)
                values (:p, :c, cast(:f as date), :q, :q, :costo, 'Carga inicial de inventario', :u) returning id
                """).param("p", productoId).param("c", codigoLote).param("f", vence).param("q", cantidad).param("costo", costo)
                .param("u", actor.id()).query((rs, i) -> rs.getObject(1, UUID.class)).single();
        kardex.registrar(loteId, productoId, "CARGA_INICIAL", cantidad, BigDecimal.ZERO, cantidad, costo, "IMPORTACION", null,
                "Carga inicial de inventario", actor.id());
        return loteId;
    }

    // ---------- Lotes ----------

    private RowMapper<LoteDto> loteMapper(int umbralPorVencer) {
        LocalDate hoy = LocalDate.now(AlertaService.ZONA);
        return (rs, i) -> {
            LocalDate vence = rs.getObject("fecha_vencimiento", LocalDate.class);
            Long dias = vence == null ? null : ChronoUnit.DAYS.between(hoy, vence);
            String vigencia = vence == null ? "SIN_VENCIMIENTO" : dias <= 0 ? "VENCIDO" : dias <= umbralPorVencer ? "POR_VENCER" : "VIGENTE";
            BigDecimal disp = rs.getBigDecimal("cantidad_disponible");
            BigDecimal costo = rs.getBigDecimal("costo_unitario");
            return new LoteDto(rs.getObject("id", UUID.class), rs.getObject("producto_id", UUID.class),
                    rs.getString("producto_codigo"), rs.getString("producto_nombre"), rs.getObject("proveedor_id", UUID.class),
                    rs.getString("codigo_lote"), vence, dias, rs.getBigDecimal("cantidad_inicial"), disp, costo,
                    disp.multiply(costo).setScale(2, java.math.RoundingMode.HALF_UP), rs.getString("estado"), vigencia,
                    rs.getObject("fecha_ingreso", OffsetDateTime.class), rs.getString("notas"));
        };
    }

    private static final String LOTE_SELECT = """
            select l.id, l.producto_id, p.codigo as producto_codigo, p.nombre as producto_nombre, l.proveedor_id, l.codigo_lote,
                   l.fecha_vencimiento, l.cantidad_inicial, l.cantidad_disponible, l.costo_unitario, l.estado, l.fecha_ingreso, l.notas
            from lotes l join productos p on p.id = l.producto_id
            """;

    @Transactional(readOnly = true)
    public LoteDto obtenerLote(UUID id) {
        return jdbc.sql(LOTE_SELECT + " where l.id = :id").param("id", id).query(loteMapper(alertas.umbralMaximoDias())).optional()
                .orElseThrow(() -> ApiException.notFound("LOTE_NO_ENCONTRADO", "Lote no encontrado"));
    }

    @Transactional(readOnly = true)
    public PageResponse<LoteDto> listarLotes(UUID productoId, String estado, Integer vencenEnDias, boolean conStock, int page, int size) {
        int tam = PageResponse.limitarTamano(size);
        int pag = Math.max(page, 0);
        String est = estado == null || estado.isBlank() ? null : estado.trim().toUpperCase(Locale.ROOT);
        if (est != null && !Set.of("ACTIVO", "AGOTADO", "BLOQUEADO").contains(est)) {
            throw ApiException.badRequest("ESTADO_INVALIDO", "Estado válido: ACTIVO, AGOTADO o BLOQUEADO");
        }
        LocalDate limite = vencenEnDias == null ? null : LocalDate.now(AlertaService.ZONA).plusDays(vencenEnDias);
        String filtros = """
                where (cast(:p as uuid) is null or l.producto_id = cast(:p as uuid))
                  and (cast(:e as text) is null or l.estado = cast(:e as text))
                  and (not :conStock or l.cantidad_disponible > 0)
                  and (cast(:lim as date) is null or (l.fecha_vencimiento is not null and l.fecha_vencimiento <= cast(:lim as date)))
                """;
        long total = jdbc.sql("select count(*) from lotes l " + filtros).param("p", productoId).param("e", est)
                .param("conStock", conStock).param("lim", limite).query(Long.class).single();
        List<LoteDto> items = jdbc.sql(LOTE_SELECT + filtros + " order by l.fecha_vencimiento nulls last, l.fecha_ingreso limit :l offset :o")
                .param("p", productoId).param("e", est).param("conStock", conStock).param("lim", limite)
                .param("l", tam).param("o", pag * tam).query(loteMapper(alertas.umbralMaximoDias())).list();
        return new PageResponse<>(items, pag, tam, total);
    }

    /** Bloqueo manual de un lote (p. ej. retiro por alerta sanitaria): deja de ser vendible hasta desbloquearlo. */
    @Transactional
    public LoteDto cambiarBloqueo(UUID loteId, boolean bloquear, String motivo) {
        var lote = jdbc.sql("select producto_id, estado, codigo_lote from lotes where id = :id for no key update").param("id", loteId)
                .query((rs, i) -> new String[]{rs.getString("producto_id"), rs.getString("estado"), rs.getString("codigo_lote")}).optional()
                .orElseThrow(() -> ApiException.notFound("LOTE_NO_ENCONTRADO", "Lote no encontrado"));
        boolean yaBloqueado = "BLOQUEADO".equals(lote[1]);
        if (bloquear == yaBloqueado) {
            throw ApiException.conflict(bloquear ? "LOTE_YA_BLOQUEADO" : "LOTE_NO_BLOQUEADO",
                    bloquear ? "El lote ya está bloqueado" : "El lote no está bloqueado");
        }
        jdbc.sql("update lotes set estado = :e where id = :id").param("e", bloquear ? "BLOQUEADO" : "ACTIVO").param("id", loteId).update();
        Map<String, Object> det = new LinkedHashMap<>();
        det.put("lote", lote[2]);
        det.put("motivo", motivo == null ? "" : motivo.trim());
        audit.registrar(bloquear ? "BLOQUEAR_LOTE" : "DESBLOQUEAR_LOTE", "lotes", loteId.toString(), det);
        alertas.evaluarProducto(UUID.fromString(lote[0]));
        return obtenerLote(loteId);
    }

    // ---------- Mermas y ajustes ----------

    @Transactional
    public AjusteDto ajustar(AjusteRequest r) {
        AuthUser actor = CurrentUser.require();
        String tipo = r.tipo().trim().toUpperCase(Locale.ROOT);
        String sentido = r.sentido().trim().toUpperCase(Locale.ROOT);
        if (!TIPOS_AJUSTE.contains(tipo)) {
            throw ApiException.badRequest("TIPO_AJUSTE_INVALIDO", "Tipo válido: " + String.join(", ", new java.util.TreeSet<>(TIPOS_AJUSTE)));
        }
        if (!sentido.equals("NEGATIVO") && !sentido.equals("POSITIVO")) {
            throw ApiException.badRequest("SENTIDO_INVALIDO", "El sentido debe ser NEGATIVO o POSITIVO");
        }
        if ((tipo.equals("VENCIMIENTO") || tipo.equals("DANO")) && sentido.equals("POSITIVO")) {
            throw ApiException.badRequest("AJUSTE_INVALIDO", "Las mermas por vencimiento o daño solo pueden disminuir existencias");
        }
        var lote = jdbc.sql("select producto_id, cantidad_disponible, costo_unitario from lotes where id = :id for no key update")
                .param("id", r.loteId())
                .query((rs, i) -> new Object[]{rs.getObject("producto_id", UUID.class), rs.getBigDecimal("cantidad_disponible"),
                        rs.getBigDecimal("costo_unitario")}).optional()
                .orElseThrow(() -> ApiException.notFound("LOTE_NO_ENCONTRADO", "Lote no encontrado"));
        UUID productoId = (UUID) lote[0];
        BigDecimal disponible = (BigDecimal) lote[1];
        BigDecimal costo = (BigDecimal) lote[2];
        boolean negativo = sentido.equals("NEGATIVO");
        if (negativo && r.cantidad().compareTo(disponible) > 0) {
            throw ApiException.unprocessable("STOCK_INSUFICIENTE", "La cantidad supera las existencias del lote (" + disponible.stripTrailingZeros().toPlainString() + ")");
        }
        BigDecimal delta = negativo ? r.cantidad().negate() : r.cantidad();
        BigDecimal nuevo = disponible.add(delta);
        jdbc.sql("update lotes set cantidad_disponible = :n where id = :id").param("n", nuevo).param("id", r.loteId()).update();
        String tipoMov = !negativo ? "AJUSTE_POSITIVO" : (tipo.equals("VENCIMIENTO") || tipo.equals("DANO")) ? "MERMA" : "AJUSTE_NEGATIVO";
        long movId = kardex.registrar(r.loteId(), productoId, tipoMov, delta, disponible, nuevo, costo, "AJUSTE", null,
                r.motivo().trim(), actor.id());
        UUID ajusteId = jdbc.sql("""
                insert into ajustes_inventario(lote_id, producto_id, tipo, sentido, cantidad, motivo, costo_unitario, movimiento_id, usuario_id)
                values (:l, :p, :t, :s, :c, :m, :costo, :mov, :u) returning id
                """).param("l", r.loteId()).param("p", productoId).param("t", tipo).param("s", sentido).param("c", r.cantidad())
                .param("m", r.motivo().trim()).param("costo", costo).param("mov", movId).param("u", actor.id())
                .query((rs, i) -> rs.getObject(1, UUID.class)).single();
        Map<String, Object> det = new LinkedHashMap<>();
        det.put("tipo", tipo);
        det.put("sentido", sentido);
        det.put("cantidad", r.cantidad());
        det.put("valor", r.cantidad().multiply(costo).setScale(2, java.math.RoundingMode.HALF_UP));
        det.put("motivo", r.motivo().trim());
        audit.registrar("CREAR_AJUSTE", "ajustes_inventario", ajusteId.toString(), det);
        alertas.evaluarProducto(productoId);
        return jdbc.sql(AJUSTE_SELECT + " where a.id = :id").param("id", ajusteId).query(AJUSTE_MAPPER).single();
    }

    private static final String AJUSTE_SELECT = """
            select a.id, a.lote_id, l.codigo_lote, a.producto_id, p.nombre as producto, a.tipo, a.sentido, a.cantidad, a.motivo,
                   a.costo_unitario, a.valor_total, a.usuario_id, u.username, a.creado_en
            from ajustes_inventario a join lotes l on l.id = a.lote_id join productos p on p.id = a.producto_id
            join usuarios u on u.id = a.usuario_id
            """;

    private static final RowMapper<AjusteDto> AJUSTE_MAPPER = (rs, i) -> new AjusteDto(rs.getObject("id", UUID.class),
            rs.getObject("lote_id", UUID.class), rs.getString("codigo_lote"), rs.getObject("producto_id", UUID.class),
            rs.getString("producto"), rs.getString("tipo"), rs.getString("sentido"), rs.getBigDecimal("cantidad"),
            rs.getString("motivo"), rs.getBigDecimal("costo_unitario"), rs.getBigDecimal("valor_total"),
            rs.getObject("usuario_id", UUID.class), rs.getString("username"), rs.getObject("creado_en", OffsetDateTime.class));

    @Transactional(readOnly = true)
    public PageResponse<AjusteDto> listarAjustes(UUID productoId, LocalDate desde, LocalDate hasta, int page, int size) {
        int tam = PageResponse.limitarTamano(size);
        int pag = Math.max(page, 0);
        String filtros = """
                where (cast(:p as uuid) is null or a.producto_id = cast(:p as uuid))
                  and (cast(:d as date) is null or a.creado_en >= (cast(:d as date)::timestamp at time zone 'America/Bogota'))
                  and (cast(:h as date) is null or a.creado_en < ((cast(:h as date) + 1)::timestamp at time zone 'America/Bogota'))
                """;
        long total = jdbc.sql("select count(*) from ajustes_inventario a " + filtros).param("p", productoId).param("d", desde)
                .param("h", hasta).query(Long.class).single();
        List<AjusteDto> items = jdbc.sql(AJUSTE_SELECT + filtros + " order by a.creado_en desc limit :l offset :o")
                .param("p", productoId).param("d", desde).param("h", hasta).param("l", tam).param("o", pag * tam)
                .query(AJUSTE_MAPPER).list();
        return new PageResponse<>(items, pag, tam, total);
    }

    // ---------- Kardex ----------

    @Transactional(readOnly = true)
    public PageResponse<MovimientoDto> kardex(UUID productoId, UUID loteId, String tipo, LocalDate desde, LocalDate hasta,
                                              int page, int size) {
        int tam = PageResponse.limitarTamano(size);
        int pag = Math.max(page, 0);
        String t = tipo == null || tipo.isBlank() ? null : tipo.trim().toUpperCase(Locale.ROOT);
        String filtros = """
                where (cast(:p as uuid) is null or m.producto_id = cast(:p as uuid))
                  and (cast(:l as uuid) is null or m.lote_id = cast(:l as uuid))
                  and (cast(:t as text) is null or m.tipo = cast(:t as text))
                  and (cast(:d as date) is null or m.creado_en >= (cast(:d as date)::timestamp at time zone 'America/Bogota'))
                  and (cast(:h as date) is null or m.creado_en < ((cast(:h as date) + 1)::timestamp at time zone 'America/Bogota'))
                """;
        long total = jdbc.sql("select count(*) from movimientos_inventario m " + filtros).param("p", productoId).param("l", loteId)
                .param("t", t).param("d", desde).param("h", hasta).query(Long.class).single();
        List<MovimientoDto> items = jdbc.sql("""
                select m.id, m.lote_id, l.codigo_lote, m.producto_id, p.nombre as producto, m.tipo, m.cantidad, m.stock_lote_antes,
                       m.stock_lote_despues, m.costo_unitario, m.referencia_tipo, m.referencia_id, m.motivo, m.usuario_id, m.creado_en
                from movimientos_inventario m join lotes l on l.id = m.lote_id join productos p on p.id = m.producto_id
                """ + filtros + " order by m.id desc limit :lim offset :o")
                .param("p", productoId).param("l", loteId).param("t", t).param("d", desde).param("h", hasta)
                .param("lim", tam).param("o", pag * tam)
                .query((rs, i) -> new MovimientoDto(rs.getLong("id"), rs.getObject("lote_id", UUID.class), rs.getString("codigo_lote"),
                        rs.getObject("producto_id", UUID.class), rs.getString("producto"), rs.getString("tipo"),
                        rs.getBigDecimal("cantidad"), rs.getBigDecimal("stock_lote_antes"), rs.getBigDecimal("stock_lote_despues"),
                        rs.getBigDecimal("costo_unitario"), rs.getString("referencia_tipo"), rs.getString("referencia_id"),
                        rs.getString("motivo"), rs.getObject("usuario_id", UUID.class), rs.getObject("creado_en", OffsetDateTime.class)))
                .list();
        return new PageResponse<>(items, pag, tam, total);
    }
}
