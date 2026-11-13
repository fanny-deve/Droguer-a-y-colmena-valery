package co.valery.pos.analitica;

import co.valery.pos.auditoria.AuditService;
import co.valery.pos.common.PageResponse;
import co.valery.pos.config.ConfiguracionService;
import co.valery.pos.inventario.AlertaService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Clasificación ABC por valor de ventas (RF-14). Ordena los productos por ingresos de la ventana configurada
 * (ventas del sistema + histórico digitalizado) y asigna A / B / C según el porcentaje acumulado.
 * Un producto cae en la categoría cuyo umbral aún no había sido superado ANTES de sumarlo, de modo que el
 * producto que cruza el umbral permanece en la categoría superior.
 */
@Service
public class AbcService {
    public record ResultadoAbc(UUID corridaId, LocalDate desde, LocalDate hasta, int productos, Map<String, Integer> porCategoria,
                               OffsetDateTime calculadoEn) {}

    public record FilaAbc(UUID productoId, String codigo, String nombre, String categoria, BigDecimal valorConsumo,
                          BigDecimal participacion, BigDecimal participacionAcumulada, LocalDate periodoInicio, LocalDate periodoFin,
                          OffsetDateTime calculadoEn) {}

    public record ResumenAbc(String categoria, long productos, BigDecimal valor, BigDecimal participacion) {}

    private record Fila(UUID id, BigDecimal valor) {}

    private final JdbcClient jdbc;
    private final ConfiguracionService config;
    private final AuditService audit;

    public AbcService(JdbcClient jdbc, ConfiguracionService config, AuditService audit) {
        this.jdbc = jdbc;
        this.config = config;
        this.audit = audit;
    }

    @Transactional
    public ResultadoAbc calcular() {
        int ventana = Math.max(config.getInt("abc.ventana_dias", 90), 7);
        BigDecimal umbralA = config.getDecimal("abc.umbral_a", BigDecimal.valueOf(80));
        BigDecimal umbralB = config.getDecimal("abc.umbral_b", BigDecimal.valueOf(95));
        LocalDate hasta = LocalDate.now(AlertaService.ZONA);
        LocalDate desde = hasta.minusDays(ventana - 1L);

        List<Fila> filas = jdbc.sql("""
                select p.id, coalesce(sum(d.ingresos), 0) as valor
                from productos p left join v_demanda_diaria d on d.producto_id = p.id and d.fecha between :d and :h
                where p.activo group by p.id order by valor desc, p.id
                """).param("d", desde).param("h", hasta)
                .query((rs, i) -> new Fila(rs.getObject("id", UUID.class), rs.getBigDecimal("valor"))).list();
        BigDecimal total = filas.stream().map(Fila::valor).reduce(BigDecimal.ZERO, BigDecimal::add);

        UUID corrida = UUID.randomUUID();
        Map<String, Integer> conteo = new LinkedHashMap<>(Map.of("A", 0, "B", 0, "C", 0));
        BigDecimal acumulado = BigDecimal.ZERO;
        OffsetDateTime ahora = jdbc.sql("select now()").query((rs, i) -> rs.getObject(1, OffsetDateTime.class)).single();
        for (Fila f : filas) {
            BigDecimal participacion = total.signum() == 0 ? BigDecimal.ZERO
                    : f.valor().multiply(BigDecimal.valueOf(100)).divide(total, 4, RoundingMode.HALF_UP);
            String cat;
            if (f.valor().signum() == 0 || total.signum() == 0) cat = "C";
            else if (acumulado.compareTo(umbralA) < 0) cat = "A";
            else if (acumulado.compareTo(umbralB) < 0) cat = "B";
            else cat = "C";
            acumulado = acumulado.add(participacion);
            conteo.merge(cat, 1, Integer::sum);
            jdbc.sql("update productos set clasificacion_abc = :c, abc_actualizado_en = :t where id = :id")
                    .param("c", cat).param("t", ahora).param("id", f.id()).update();
            jdbc.sql("""
                    insert into clasificacion_abc_historial(corrida_id, producto_id, categoria, valor_consumo, participacion,
                                                            participacion_acumulada, periodo_inicio, periodo_fin, calculado_en)
                    values (:co, :p, :c, :v, :pa, :ac, :d, :h, :t)
                    """).param("co", corrida).param("p", f.id()).param("c", cat).param("v", f.valor())
                    .param("pa", participacion).param("ac", acumulado.min(BigDecimal.valueOf(100))).param("d", desde).param("h", hasta)
                    .param("t", ahora).update();
        }
        audit.registrar("CALCULAR_ABC", "clasificacion_abc_historial", corrida.toString(),
                Map.of("productos", filas.size(), "A", conteo.get("A"), "B", conteo.get("B"), "C", conteo.get("C"),
                        "desde", desde.toString(), "hasta", hasta.toString()));
        return new ResultadoAbc(corrida, desde, hasta, filas.size(), conteo, ahora);
    }

    /** Evaluación periódica: solo una instancia a la vez. */
    @Transactional
    public ResultadoAbc calcularSiLibre() {
        Boolean libre = jdbc.sql("select pg_try_advisory_xact_lock(727003)").query((rs, i) -> rs.getBoolean(1)).single();
        return Boolean.TRUE.equals(libre) ? calcular() : null;
    }

    @Transactional(readOnly = true)
    public PageResponse<FilaAbc> consultar(String categoria, int page, int size) {
        int tam = PageResponse.limitarTamano(size);
        int pag = Math.max(page, 0);
        String cat = categoria == null || categoria.isBlank() ? null : categoria.trim().toUpperCase(Locale.ROOT);
        String base = """
                from clasificacion_abc_historial h join productos p on p.id = h.producto_id
                where h.corrida_id = (select corrida_id from clasificacion_abc_historial order by calculado_en desc limit 1)
                  and (cast(:c as text) is null or h.categoria = cast(:c as text))
                """;
        long total = jdbc.sql("select count(*) " + base).param("c", cat).query(Long.class).single();
        List<FilaAbc> items = jdbc.sql("""
                select p.id, p.codigo, p.nombre, h.categoria, h.valor_consumo, h.participacion, h.participacion_acumulada,
                       h.periodo_inicio, h.periodo_fin, h.calculado_en
                """ + base + " order by h.valor_consumo desc, p.nombre limit :l offset :o")
                .param("c", cat).param("l", tam).param("o", pag * tam)
                .query((rs, i) -> new FilaAbc(rs.getObject("id", UUID.class), rs.getString("codigo"), rs.getString("nombre"),
                        rs.getString("categoria"), rs.getBigDecimal("valor_consumo"), rs.getBigDecimal("participacion"),
                        rs.getBigDecimal("participacion_acumulada"), rs.getObject("periodo_inicio", LocalDate.class),
                        rs.getObject("periodo_fin", LocalDate.class), rs.getObject("calculado_en", OffsetDateTime.class))).list();
        return new PageResponse<>(items, pag, tam, total);
    }

    @Transactional(readOnly = true)
    public List<ResumenAbc> resumen() {
        List<ResumenAbc> base = jdbc.sql("""
                select h.categoria, count(*) as n, coalesce(sum(h.valor_consumo), 0) as valor
                from clasificacion_abc_historial h
                where h.corrida_id = (select corrida_id from clasificacion_abc_historial order by calculado_en desc limit 1)
                group by h.categoria order by h.categoria
                """).query((rs, i) -> new ResumenAbc(rs.getString("categoria"), rs.getLong("n"), rs.getBigDecimal("valor"), BigDecimal.ZERO)).list();
        BigDecimal total = base.stream().map(ResumenAbc::valor).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<ResumenAbc> r = new ArrayList<>();
        for (ResumenAbc a : base) {
            r.add(new ResumenAbc(a.categoria(), a.productos(), a.valor(), total.signum() == 0 ? BigDecimal.ZERO
                    : a.valor().multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP)));
        }
        return r;
    }
}
