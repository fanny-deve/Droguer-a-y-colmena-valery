package co.valery.pos.inventario;

import co.valery.pos.common.ApiException;
import co.valery.pos.common.PageResponse;
import co.valery.pos.config.ConfiguracionService;
import co.valery.pos.inventario.InventarioDtos.AlertaDto;
import co.valery.pos.inventario.InventarioDtos.ResultadoEvaluacion;
import co.valery.pos.security.CurrentUser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alertas automáticas de vencimiento (30/15/7 días y vencido) y de reabastecimiento (punto de reorden y stock mínimo).
 * Mantiene UNA alerta abierta por lote y por producto: si la situación cambia, la anterior se resuelve (RF-11, RF-12).
 */
@Service
public class AlertaService {
    public static final ZoneId ZONA = ZoneId.of("America/Bogota");
    private static final String SIN_LOTE = "00000000-0000-0000-0000-000000000000";

    private record LoteFila(UUID id, UUID productoId, String codigo, String producto, LocalDate vence, BigDecimal cantidad, String estado) {}

    private record ProductoStock(UUID id, String nombre, BigDecimal minimo, BigDecimal reorden, BigDecimal vendible) {}

    private final JdbcClient jdbc;
    private final ConfiguracionService config;

    public AlertaService(JdbcClient jdbc, ConfiguracionService config) {
        this.jdbc = jdbc;
        this.config = config;
    }

    @Transactional
    public ResultadoEvaluacion evaluarTodo() {
        return evaluar(null);
    }

    /** Evaluación periódica: solo una instancia a la vez (candado consultivo de PostgreSQL). */
    @Transactional
    public ResultadoEvaluacion evaluarTodoSiLibre() {
        Boolean libre = jdbc.sql("select pg_try_advisory_xact_lock(727002)").query((rs, i) -> rs.getBoolean(1)).single();
        return Boolean.TRUE.equals(libre) ? evaluar(null) : new ResultadoEvaluacion(0, 0);
    }

    @Transactional
    public ResultadoEvaluacion evaluarProducto(UUID productoId) {
        return evaluar(productoId);
    }

    private ResultadoEvaluacion evaluar(UUID productoId) {
        int[] c = {0, 0};
        evaluarLotes(productoId, c);
        evaluarStock(productoId, c);
        return new ResultadoEvaluacion(c[0], c[1]);
    }

    private List<Integer> umbrales() {
        List<Integer> u = new ArrayList<>(config.getIntList("alertas.dias_vencimiento", List.of(30, 15, 7)));
        if (u.size() != 3 || u.stream().anyMatch(x -> x == null || x <= 0)) u = new ArrayList<>(List.of(30, 15, 7));
        Collections.sort(u);
        return u;
    }

    public int umbralMaximoDias() {
        List<Integer> u = umbrales();
        return u.get(2);
    }

    private void evaluarLotes(UUID productoId, int[] c) {
        List<Integer> u = umbrales();
        LocalDate hoy = LocalDate.now(ZONA);
        List<LoteFila> lotes = jdbc.sql("""
                select l.id, l.producto_id, l.codigo_lote, p.nombre as producto, l.fecha_vencimiento, l.cantidad_disponible, l.estado
                from lotes l join productos p on p.id = l.producto_id
                where (cast(:p as uuid) is null or l.producto_id = cast(:p as uuid))
                  and (l.fecha_vencimiento is not null
                       or exists (select 1 from alertas a where a.lote_id = l.id and a.estado <> 'RESUELTA'))
                """).param("p", productoId)
                .query((rs, i) -> new LoteFila(rs.getObject("id", UUID.class), rs.getObject("producto_id", UUID.class),
                        rs.getString("codigo_lote"), rs.getString("producto"), rs.getObject("fecha_vencimiento", LocalDate.class),
                        rs.getBigDecimal("cantidad_disponible"), rs.getString("estado"))).list();
        for (LoteFila l : lotes) {
            String deseado = null;
            long dias = 0;
            if (l.cantidad().signum() > 0 && !"BLOQUEADO".equals(l.estado()) && l.vence() != null) {
                dias = ChronoUnit.DAYS.between(hoy, l.vence());
                if (dias <= 0) deseado = "VENCIDO";
                else if (dias <= u.get(0)) deseado = "VENCIMIENTO_7";
                else if (dias <= u.get(1)) deseado = "VENCIMIENTO_15";
                else if (dias <= u.get(2)) deseado = "VENCIMIENTO_30";
            }
            c[1] += jdbc.sql("""
                    update alertas set estado = 'RESUELTA', resuelta_en = now()
                    where lote_id = :l and estado <> 'RESUELTA' and (cast(:t as text) is null or tipo <> cast(:t as text))
                    """).param("l", l.id()).param("t", deseado).update();
            if (deseado != null) {
                String sev = switch (deseado) {
                    case "VENCIDO", "VENCIMIENTO_7" -> "CRITICA";
                    case "VENCIMIENTO_15" -> "ADVERTENCIA";
                    default -> "INFO";
                };
                String msg = deseado.equals("VENCIDO")
                        ? "El lote " + l.codigo() + " de '" + l.producto() + "' está vencido (" + l.vence() + ") y no puede venderse"
                        : "El lote " + l.codigo() + " de '" + l.producto() + "' vence el " + l.vence();
                c[0] += crear(deseado, l.productoId(), l.id(), sev, msg,
                        "{\"fechaVencimiento\":\"" + l.vence() + "\",\"cantidad\":" + l.cantidad().toPlainString() + "}");
            }
        }
    }

    private void evaluarStock(UUID productoId, int[] c) {
        List<ProductoStock> prods = jdbc.sql("""
                select p.id, p.nombre, p.stock_minimo, p.punto_reorden, s.stock_vendible
                from productos p join v_stock_productos s on s.producto_id = p.id
                where p.activo and (cast(:p as uuid) is null or p.id = cast(:p as uuid))
                """).param("p", productoId)
                .query((rs, i) -> new ProductoStock(rs.getObject("id", UUID.class), rs.getString("nombre"),
                        rs.getBigDecimal("stock_minimo"), rs.getBigDecimal("punto_reorden"), rs.getBigDecimal("stock_vendible"))).list();
        for (ProductoStock p : prods) {
            String deseado = null;
            if (p.minimo().signum() > 0 && p.vendible().compareTo(p.minimo()) <= 0) deseado = "STOCK_MINIMO";
            else if (p.reorden().signum() > 0 && p.vendible().compareTo(p.reorden()) <= 0) deseado = "PUNTO_REORDEN";
            c[1] += jdbc.sql("""
                    update alertas set estado = 'RESUELTA', resuelta_en = now()
                    where producto_id = :p and lote_id is null and estado <> 'RESUELTA'
                      and tipo in ('STOCK_MINIMO', 'PUNTO_REORDEN') and (cast(:t as text) is null or tipo <> cast(:t as text))
                    """).param("p", p.id()).param("t", deseado).update();
            if (deseado != null) {
                String msg = deseado.equals("STOCK_MINIMO")
                        ? "'" + p.nombre() + "' llegó al stock mínimo (" + p.minimo().stripTrailingZeros().toPlainString() + "): existencias vendibles " + p.vendible().stripTrailingZeros().toPlainString()
                        : "'" + p.nombre() + "' alcanzó el punto de reorden (" + p.reorden().stripTrailingZeros().toPlainString() + "): existencias vendibles " + p.vendible().stripTrailingZeros().toPlainString();
                c[0] += crear(deseado, p.id(), null, deseado.equals("STOCK_MINIMO") ? "CRITICA" : "ADVERTENCIA", msg,
                        "{\"stockVendible\":" + p.vendible().toPlainString() + "}");
            }
        }
    }

    /** Inserta si no hay una alerta abierta equivalente (índice único parcial + ON CONFLICT DO NOTHING). */
    private int crear(String tipo, UUID productoId, UUID loteId, String severidad, String mensaje, String datosJson) {
        return jdbc.sql("""
                insert into alertas(tipo, producto_id, lote_id, severidad, mensaje, datos)
                values (:t, :p, cast(:l as uuid), :s, :m, cast(:d as jsonb))
                on conflict do nothing
                """).param("t", tipo).param("p", productoId).param("l", loteId).param("s", severidad)
                .param("m", mensaje).param("d", datosJson).update();
    }

    // ---------- Consulta ----------

    private static final RowMapper<AlertaDto> MAPPER = (rs, i) -> {
        LocalDate vence = rs.getObject("fecha_vencimiento", LocalDate.class);
        Long dias = vence == null ? null : ChronoUnit.DAYS.between(LocalDate.now(ZONA), vence);
        return new AlertaDto(rs.getObject("id", UUID.class), rs.getString("tipo"), rs.getString("severidad"),
                rs.getString("mensaje"), rs.getString("estado"), rs.getObject("producto_id", UUID.class),
                rs.getString("producto"), rs.getObject("lote_id", UUID.class), rs.getString("codigo_lote"), vence, dias,
                rs.getObject("generada_en", OffsetDateTime.class), rs.getObject("vista_en", OffsetDateTime.class));
    };

    private static final String SELECT = """
            select a.id, a.tipo, a.severidad, a.mensaje, a.estado, a.producto_id, p.nombre as producto, a.lote_id,
                   l.codigo_lote, l.fecha_vencimiento, a.generada_en, a.vista_en
            from alertas a join productos p on p.id = a.producto_id left join lotes l on l.id = a.lote_id
            """;

    @Transactional(readOnly = true)
    public PageResponse<AlertaDto> listar(String estado, String tipo, UUID productoId, int page, int size) {
        int tam = PageResponse.limitarTamano(size);
        int pag = Math.max(page, 0);
        String est = estado == null || estado.isBlank() ? "ABIERTAS" : estado.trim().toUpperCase();
        String filtros = """
                where (cast(:est as text) = 'TODAS'
                       or (cast(:est as text) = 'ABIERTAS' and a.estado <> 'RESUELTA')
                       or a.estado = cast(:est as text))
                  and (cast(:t as text) is null or a.tipo = cast(:t as text))
                  and (cast(:p as uuid) is null or a.producto_id = cast(:p as uuid))
                """;
        String t = tipo == null || tipo.isBlank() ? null : tipo.trim().toUpperCase();
        long total = jdbc.sql("select count(*) from alertas a " + filtros).param("est", est).param("t", t).param("p", productoId)
                .query(Long.class).single();
        List<AlertaDto> items = jdbc.sql(SELECT + filtros + """
                 order by case a.severidad when 'CRITICA' then 0 when 'ADVERTENCIA' then 1 else 2 end, a.generada_en desc
                 limit :l offset :o
                """).param("est", est).param("t", t).param("p", productoId).param("l", tam).param("o", pag * tam)
                .query(MAPPER).list();
        return new PageResponse<>(items, pag, tam, total);
    }

    @Transactional(readOnly = true)
    public Map<String, Long> resumen() {
        Map<String, Long> r = new LinkedHashMap<>();
        for (String t : List.of("VENCIDO", "VENCIMIENTO_7", "VENCIMIENTO_15", "VENCIMIENTO_30", "STOCK_MINIMO", "PUNTO_REORDEN")) {
            r.put(t, 0L);
        }
        jdbc.sql("select tipo, count(*) as n from alertas where estado <> 'RESUELTA' group by tipo")
                .query((rs, i) -> {
                    r.put(rs.getString("tipo"), rs.getLong("n"));
                    return 0;
                }).list();
        return r;
    }

    @Transactional
    public AlertaDto marcarVista(UUID id) {
        int n = jdbc.sql("update alertas set estado = 'VISTA', vista_por = :u, vista_en = now() where id = :id and estado = 'ACTIVA'")
                .param("u", CurrentUser.require().id()).param("id", id).update();
        AlertaDto dto = jdbc.sql(SELECT + " where a.id = :id").param("id", id).query(MAPPER).optional()
                .orElseThrow(() -> ApiException.notFound("ALERTA_NO_ENCONTRADA", "Alerta no encontrada"));
        if (n == 0 && !"VISTA".equals(dto.estado())) {
            throw ApiException.conflict("ALERTA_NO_ACTIVA", "La alerta ya fue resuelta");
        }
        return dto;
    }
}
