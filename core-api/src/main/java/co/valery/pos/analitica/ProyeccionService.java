package co.valery.pos.analitica;

import co.valery.pos.auditoria.AuditService;
import co.valery.pos.common.ApiException;
import co.valery.pos.common.PageResponse;
import co.valery.pos.config.AppProperties;
import co.valery.pos.config.ConfiguracionService;
import co.valery.pos.config.RedisCache;
import co.valery.pos.inventario.AlertaService;
import co.valery.pos.security.CurrentUser;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Integración con el microservicio Python de proyección de demanda (RF-18). El núcleo transaccional extrae las series de
 * ventas (sistema + histórico digitalizado), las envía por API y guarda las proyecciones. Si el microservicio falla, SOLO
 * se afectan las proyecciones: ventas, inventario y reabastecimiento (que cae al promedio histórico) siguen operando.
 */
@Service
public class ProyeccionService {
    private static final Logger log = LoggerFactory.getLogger(ProyeccionService.class);
    private static final int LOTE_PRODUCTOS = 200;
    private static final int DIAS_HISTORIA = 730;

    // ----- Contrato con el microservicio (snake_case) -----
    public record ObsPy(LocalDate fecha, BigDecimal unidades) {}

    public record SeriePy(@JsonProperty("producto_id") String productoId, List<ObsPy> observaciones) {}

    public record SolicitudPy(@JsonProperty("fecha_corte") LocalDate fechaCorte,
                              @JsonProperty("horizonte_semanas") int horizonteSemanas, List<SeriePy> series) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PeriodoPy(LocalDate inicio, LocalDate fin, @JsonProperty("demanda_proyectada") BigDecimal demanda,
                            @JsonProperty("limite_inferior") BigDecimal inferior, @JsonProperty("limite_superior") BigDecimal superior) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResultadoPy(@JsonProperty("producto_id") String productoId, String modelo,
                              @JsonProperty("error_wape") BigDecimal errorWape,
                              @JsonProperty("observaciones_semanales") int observaciones, List<PeriodoPy> periodos,
                              List<String> advertencias) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RespuestaPy(@JsonProperty("version_modelo") String versionModelo, List<ResultadoPy> resultados) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SaludPy(String status, String version) {}

    // ----- API hacia el frontend -----
    public record GenerarRequest(Integer horizonteSemanas, List<UUID> productoIds) {}

    public record ResultadoGeneracion(UUID corridaId, int productos, int periodos, int horizonteSemanas, String versionModelo,
                                      int conAdvertencias, OffsetDateTime generadoEn) {}

    public record PeriodoDto(LocalDate inicio, LocalDate fin, BigDecimal demandaProyectada, BigDecimal limiteInferior, BigDecimal limiteSuperior) {}

    public record ProyeccionProductoDto(UUID productoId, String codigo, String nombre, UUID corridaId, String modelo, BigDecimal errorWape,
                                        int observacionesSemanales, List<String> advertencias, OffsetDateTime generadoEn,
                                        BigDecimal demandaTotalHorizonte, List<PeriodoDto> periodos) {}

    public record EstadoAnalitica(boolean disponible, String version, String mensaje, OffsetDateTime ultimaCorrida) {}

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final ConfiguracionService config;
    private final RedisCache cache;
    private final ObjectMapper om;
    private final AppProperties props;
    private final RestClient http;

    public ProyeccionService(JdbcClient jdbc, AuditService audit, ConfiguracionService config, RedisCache cache, ObjectMapper om,
                             AppProperties props, RestClient.Builder builder) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.config = config;
        this.cache = cache;
        this.om = om;
        this.props = props;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(90));
        this.http = builder.baseUrl(props.analytics().url()).requestFactory(factory)
                .defaultHeader("X-API-Key", props.analytics().apiKey() == null ? "" : props.analytics().apiKey()).build();
    }

    public boolean configurado() {
        return props.analytics() != null && props.analytics().apiKey() != null && !props.analytics().apiKey().isBlank();
    }

    @Transactional
    public ResultadoGeneracion generar(GenerarRequest r) {
        int horizonte = r != null && r.horizonteSemanas() != null ? Math.min(Math.max(r.horizonteSemanas(), 1), 26)
                : Math.max(config.getInt("analitica.horizonte_semanas", 4), 1);
        LocalDate hoy = LocalDate.now(AlertaService.ZONA);
        List<UUID> filtro = r == null ? null : r.productoIds();

        // 1) Series de demanda diaria de los productos activos con historia
        Map<UUID, List<ObsPy>> series = new LinkedHashMap<>();
        jdbc.sql("""
                select d.producto_id, d.fecha, sum(d.unidades) as unidades
                from v_demanda_diaria d join productos p on p.id = d.producto_id
                where p.activo and d.fecha >= :desde and d.fecha <= :hoy
                group by d.producto_id, d.fecha order by d.producto_id, d.fecha
                """).param("desde", hoy.minusDays(DIAS_HISTORIA)).param("hoy", hoy)
                .query((rs, i) -> {
                    UUID id = rs.getObject("producto_id", UUID.class);
                    if (filtro == null || filtro.isEmpty() || filtro.contains(id)) {
                        series.computeIfAbsent(id, k -> new ArrayList<>()).add(new ObsPy(rs.getObject("fecha", LocalDate.class), rs.getBigDecimal("unidades")));
                    }
                    return 0;
                }).list();
        if (series.isEmpty()) {
            throw ApiException.unprocessable("SIN_HISTORICO", "No hay historial de ventas para proyectar. Registre ventas o importe el histórico del cuaderno.");
        }

        // 2) Llamadas por lotes al microservicio
        UUID corrida = UUID.randomUUID();
        OffsetDateTime ahora = jdbc.sql("select now()").query((rs, i) -> rs.getObject(1, OffsetDateTime.class)).single();
        List<UUID> ids = new ArrayList<>(series.keySet());
        int periodos = 0, conAdv = 0, productos = 0;
        String version = "desconocida";
        UUID actor = CurrentUser.opt().map(a -> a.id()).orElse(null);
        for (int i = 0; i < ids.size(); i += LOTE_PRODUCTOS) {
            List<UUID> lote = ids.subList(i, Math.min(i + LOTE_PRODUCTOS, ids.size()));
            RespuestaPy resp = llamar(new SolicitudPy(hoy, horizonte, lote.stream().map(id -> new SeriePy(id.toString(), series.get(id))).toList()));
            version = resp.versionModelo() == null ? version : resp.versionModelo();
            for (ResultadoPy res : resp.resultados()) {
                UUID productoId = UUID.fromString(res.productoId());
                if (!series.containsKey(productoId)) continue;     // ignora productos que no se enviaron
                productos++;
                if (res.advertencias() != null && !res.advertencias().isEmpty()) conAdv++;
                String parametros = parametros(res, version, horizonte);
                for (PeriodoPy p : res.periodos()) {
                    jdbc.sql("""
                            insert into proyecciones_demanda(corrida_id, producto_id, granularidad, periodo_inicio, periodo_fin,
                                   demanda_proyectada, limite_inferior, limite_superior, modelo, error_mape, observaciones_usadas,
                                   parametros, generado_en, generado_por)
                            values (:c, :p, 'SEMANAL', :i, :f, :d, cast(:li as numeric), cast(:ls as numeric), :m, cast(:e as numeric), :o,
                                    cast(:par as jsonb), :t, cast(:u as uuid))
                            """).param("c", corrida).param("p", productoId).param("i", p.inicio()).param("f", p.fin())
                            .param("d", p.demanda().max(BigDecimal.ZERO)).param("li", p.inferior()).param("ls", p.superior())
                            .param("m", res.modelo()).param("e", res.errorWape()).param("o", res.observaciones())
                            .param("par", parametros).param("t", ahora).param("u", actor).update();
                    periodos++;
                }
            }
        }
        cache.invalidar("proyecciones");
        audit.registrar("GENERAR_PROYECCIONES", "proyecciones_demanda", corrida.toString(),
                Map.of("productos", productos, "periodos", periodos, "horizonteSemanas", horizonte, "modelo", version));
        return new ResultadoGeneracion(corrida, productos, periodos, horizonte, version, conAdv, ahora);
    }

    private String parametros(ResultadoPy res, String version, int horizonte) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("metricaError", "WAPE");
        m.put("versionModelo", version);
        m.put("horizonteSemanas", horizonte);
        m.put("advertencias", res.advertencias() == null ? List.of() : res.advertencias());
        try {
            return om.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private RespuestaPy llamar(SolicitudPy solicitud) {
        if (!configurado()) {
            throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "SERVICIO_ANALITICA_NO_CONFIGURADO",
                    "El microservicio de analítica no está configurado (ANALYTICS_API_KEY)");
        }
        try {
            RespuestaPy r = http.post().uri("/v1/proyecciones").body(solicitud).retrieve().body(RespuestaPy.class);
            if (r == null || r.resultados() == null) throw new RestClientException("Respuesta vacía del microservicio");
            return r;
        } catch (RestClientException e) {
            log.warn("Microservicio de analítica no disponible: {}", e.getMessage());
            throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "SERVICIO_ANALITICA_NO_DISPONIBLE",
                    "El servicio de proyección no está disponible en este momento. Las ventas y el inventario no se ven afectados; intente más tarde.");
        }
    }

    @Transactional(readOnly = true)
    public EstadoAnalitica estado() {
        OffsetDateTime ultima = jdbc.sql("select max(generado_en) from proyecciones_demanda")
                .query((rs, i) -> rs.getObject(1, OffsetDateTime.class)).single();
        if (!configurado()) return new EstadoAnalitica(false, null, "Sin ANALYTICS_API_KEY configurada", ultima);
        try {
            SaludPy s = http.get().uri("/health").retrieve().body(SaludPy.class);
            return new EstadoAnalitica(s != null && "ok".equals(s.status()), s == null ? null : s.version(), "Operativo", ultima);
        } catch (RestClientException e) {
            return new EstadoAnalitica(false, null, "No responde", ultima);
        }
    }

    // ---------- Consulta (con caché) ----------

    @Transactional(readOnly = true)
    public ProyeccionProductoDto consultar(UUID productoId) {
        String clave = "proy:" + cache.version("proyecciones") + ":" + productoId;
        var enCache = cache.leer(clave, new TypeReference<ProyeccionProductoDto>() {});
        if (enCache.isPresent()) return enCache.get();
        ProyeccionProductoDto dto = jdbc.sql("""
                select p.id, p.codigo, p.nombre, x.corrida_id, x.modelo, x.error_mape, x.observaciones_usadas, x.generado_en,
                       x.parametros::text as parametros
                from proyecciones_demanda x join productos p on p.id = x.producto_id
                where x.producto_id = :p order by x.generado_en desc, x.periodo_inicio limit 1
                """).param("p", productoId)
                .query((rs, i) -> {
                    List<String> adv = List.of();
                    try {
                        var n = om.readTree(rs.getString("parametros")).get("advertencias");
                        if (n != null) adv = om.convertValue(n, new TypeReference<List<String>>() {});
                    } catch (JsonProcessingException e) {
                        adv = List.of();
                    }
                    return new ProyeccionProductoDto(rs.getObject("id", UUID.class), rs.getString("codigo"), rs.getString("nombre"),
                            rs.getObject("corrida_id", UUID.class), rs.getString("modelo"), rs.getBigDecimal("error_mape"),
                            rs.getInt("observaciones_usadas"), adv, rs.getObject("generado_en", OffsetDateTime.class), BigDecimal.ZERO, List.of());
                }).optional()
                .orElseThrow(() -> ApiException.notFound("SIN_PROYECCION", "Este producto aún no tiene proyecciones generadas"));
        List<PeriodoDto> periodos = jdbc.sql("""
                select periodo_inicio, periodo_fin, demanda_proyectada, limite_inferior, limite_superior from proyecciones_demanda
                where producto_id = :p and corrida_id = :c order by periodo_inicio
                """).param("p", productoId).param("c", dto.corridaId())
                .query((rs, i) -> new PeriodoDto(rs.getObject("periodo_inicio", LocalDate.class), rs.getObject("periodo_fin", LocalDate.class),
                        rs.getBigDecimal("demanda_proyectada"), rs.getBigDecimal("limite_inferior"), rs.getBigDecimal("limite_superior"))).list();
        BigDecimal total = periodos.stream().map(PeriodoDto::demandaProyectada).reduce(BigDecimal.ZERO, BigDecimal::add);
        ProyeccionProductoDto completo = new ProyeccionProductoDto(dto.productoId(), dto.codigo(), dto.nombre(), dto.corridaId(), dto.modelo(),
                dto.errorWape(), dto.observacionesSemanales(), dto.advertencias(), dto.generadoEn(), total, periodos);
        cache.guardar(clave, completo, Duration.ofHours(1));
        return completo;
    }

    public record FilaProyeccion(UUID productoId, String codigo, String nombre, String modelo, BigDecimal errorWape,
                                 BigDecimal demandaTotalHorizonte, OffsetDateTime generadoEn) {}

    /** Resumen de la última corrida por producto, ordenado por demanda proyectada. */
    @Transactional(readOnly = true)
    public PageResponse<FilaProyeccion> listarUltima(int page, int size) {
        int tam = PageResponse.limitarTamano(size);
        int pag = Math.max(page, 0);
        String base = """
                from proyecciones_demanda x join productos p on p.id = x.producto_id
                where x.corrida_id = (select corrida_id from proyecciones_demanda order by generado_en desc limit 1)
                """;
        long total = jdbc.sql("select count(distinct x.producto_id) " + base).query(Long.class).single();
        List<FilaProyeccion> items = jdbc.sql("""
                select p.id, p.codigo, p.nombre, max(x.modelo) as modelo, max(x.error_mape) as wape, sum(x.demanda_proyectada) as demanda,
                       max(x.generado_en) as generado
                """ + base + " group by p.id, p.codigo, p.nombre order by demanda desc, p.nombre limit :l offset :o")
                .param("l", tam).param("o", pag * tam)
                .query((rs, i) -> new FilaProyeccion(rs.getObject("id", UUID.class), rs.getString("codigo"), rs.getString("nombre"),
                        rs.getString("modelo"), rs.getBigDecimal("wape"), rs.getBigDecimal("demanda"),
                        rs.getObject("generado", OffsetDateTime.class))).list();
        return new PageResponse<>(items, pag, tam, total);
    }

    /** Generación periódica: una instancia a la vez y solo si el microservicio está configurado. */
    @Transactional
    public ResultadoGeneracion generarSiLibre() {
        if (!configurado()) return null;
        Boolean libre = jdbc.sql("select pg_try_advisory_xact_lock(727004)").query((rs, i) -> rs.getBoolean(1)).single();
        return Boolean.TRUE.equals(libre) ? generar(null) : null;
    }
}
