package co.valery.pos.ventas;

import co.valery.pos.auditoria.AuditService;
import co.valery.pos.common.ApiException;
import co.valery.pos.common.PageResponse;
import co.valery.pos.security.AuthUser;
import co.valery.pos.security.CurrentUser;
import co.valery.pos.ventas.VentaDtos.AbrirCajaRequest;
import co.valery.pos.ventas.VentaDtos.CerrarCajaRequest;
import co.valery.pos.ventas.VentaDtos.CierreForzadoRequest;
import co.valery.pos.ventas.VentaDtos.ResumenCaja;
import co.valery.pos.ventas.VentaDtos.SesionCajaDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Apertura y cierre de caja con arqueo: resumen diario de ventas y diferencia de efectivo (RF-21, iteración de cierre de caja). */
@Service
public class CajaService {
    private record Fila(UUID id, UUID cajeroId, String cajero, String estado, OffsetDateTime abiertaEn, BigDecimal fondo,
                        OffsetDateTime cerradaEn, BigDecimal esperado, BigDecimal contado, BigDecimal diferencia, String obs) {}

    private static final String SELECT = """
            select s.id, s.cajero_id, u.nombre_completo as cajero, s.estado, s.abierta_en, s.fondo_inicial, s.cerrada_en,
                   s.efectivo_esperado, s.efectivo_contado, s.diferencia, s.observaciones
            from sesiones_caja s join usuarios u on u.id = s.cajero_id
            """;

    private final JdbcClient jdbc;
    private final AuditService audit;

    public CajaService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    private static Fila mapear(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Fila(rs.getObject("id", UUID.class), rs.getObject("cajero_id", UUID.class), rs.getString("cajero"),
                rs.getString("estado"), rs.getObject("abierta_en", OffsetDateTime.class), rs.getBigDecimal("fondo_inicial"),
                rs.getObject("cerrada_en", OffsetDateTime.class), rs.getBigDecimal("efectivo_esperado"),
                rs.getBigDecimal("efectivo_contado"), rs.getBigDecimal("diferencia"), rs.getString("observaciones"));
    }

    private Fila fila(UUID id) {
        return jdbc.sql(SELECT + " where s.id = :id").param("id", id).query((rs, i) -> mapear(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("SESION_NO_ENCONTRADA", "Sesión de caja no encontrada"));
    }

    private ResumenCaja resumen(UUID sesionId, BigDecimal fondo) {
        Map<String, BigDecimal> por = new LinkedHashMap<>();
        long[] contadores = {0, 0};
        BigDecimal[] total = {BigDecimal.ZERO};
        jdbc.sql("select forma_pago, estado, count(*) as n, coalesce(sum(total), 0) as t from ventas where sesion_caja_id = :s group by forma_pago, estado")
                .param("s", sesionId).query((rs, i) -> {
                    if ("CONFIRMADA".equals(rs.getString("estado"))) {
                        contadores[0] += rs.getLong("n");
                        total[0] = total[0].add(rs.getBigDecimal("t"));
                        por.merge(rs.getString("forma_pago"), rs.getBigDecimal("t"), BigDecimal::add);
                    } else {
                        contadores[1] += rs.getLong("n");
                    }
                    return 0;
                }).list();
        BigDecimal efectivo = por.getOrDefault("EFECTIVO", BigDecimal.ZERO);
        return new ResumenCaja(contadores[0], contadores[1], total[0].setScale(2, RoundingMode.HALF_UP), por,
                fondo.add(efectivo).setScale(2, RoundingMode.HALF_UP));
    }

    private SesionCajaDto dto(Fila f) {
        return new SesionCajaDto(f.id(), f.cajeroId(), f.cajero(), f.estado(), f.abiertaEn(), f.fondo(), f.cerradaEn(), f.esperado(),
                f.contado(), f.diferencia(), f.obs(), resumen(f.id(), f.fondo()));
    }

    @Transactional
    public SesionCajaDto abrir(AbrirCajaRequest r) {
        AuthUser actor = CurrentUser.require();
        if (jdbc.sql("select count(*) from sesiones_caja where cajero_id = :c and estado = 'ABIERTA'").param("c", actor.id())
                .query(Long.class).single() > 0) {
            throw ApiException.conflict("CAJA_YA_ABIERTA", "Ya tiene una caja abierta; ciérrela antes de abrir otra");
        }
        UUID id = jdbc.sql("insert into sesiones_caja(cajero_id, fondo_inicial) values (:c, :f) returning id")
                .param("c", actor.id()).param("f", r.fondoInicial()).query((rs, i) -> rs.getObject(1, UUID.class)).single();
        audit.registrar("ABRIR_CAJA", "sesiones_caja", id.toString(), Map.of("fondoInicial", r.fondoInicial()));
        return dto(fila(id));
    }

    @Transactional(readOnly = true)
    public SesionCajaDto actual() {
        AuthUser actor = CurrentUser.require();
        Fila f = jdbc.sql(SELECT + " where s.cajero_id = :c and s.estado = 'ABIERTA'").param("c", actor.id())
                .query((rs, i) -> mapear(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("CAJA_NO_ABIERTA", "No tiene una caja abierta"));
        return dto(f);
    }

    @Transactional
    public SesionCajaDto cerrar(CerrarCajaRequest r) {
        AuthUser actor = CurrentUser.require();
        UUID id = jdbc.sql("select id from sesiones_caja where cajero_id = :c and estado = 'ABIERTA' for update").param("c", actor.id())
                .query((rs, i) -> rs.getObject(1, UUID.class)).optional()
                .orElseThrow(() -> ApiException.notFound("CAJA_NO_ABIERTA", "No tiene una caja abierta"));
        Fila f = fila(id);
        ResumenCaja res = resumen(id, f.fondo());
        BigDecimal contado = r.efectivoContado().setScale(2, RoundingMode.HALF_UP);
        BigDecimal diferencia = contado.subtract(res.efectivoEsperado());
        String obs = r.observaciones() == null ? null : r.observaciones().trim();
        if (diferencia.signum() != 0 && (obs == null || obs.length() < 5)) {
            throw ApiException.badRequest("OBSERVACION_REQUERIDA",
                    "Hay una diferencia de " + diferencia.toPlainString() + " entre el efectivo contado y el esperado; explíquela en las observaciones");
        }
        jdbc.sql("""
                update sesiones_caja set estado = 'CERRADA', cerrada_en = now(), efectivo_esperado = :e, efectivo_contado = :c,
                       diferencia = :d, observaciones = cast(:o as text) where id = :id
                """).param("e", res.efectivoEsperado()).param("c", contado).param("d", diferencia).param("o", obs).param("id", id).update();
        Map<String, Object> det = new LinkedHashMap<>();
        det.put("esperado", res.efectivoEsperado());
        det.put("contado", contado);
        det.put("diferencia", diferencia);
        det.put("ventas", res.ventas());
        det.put("totalVentas", res.totalVentas());
        audit.registrar("CERRAR_CAJA", "sesiones_caja", id.toString(), det);
        return dto(fila(id));
    }

    /** La administradora cierra la caja de una cajera ausente (p. ej. para poder darla de baja). Queda auditado. */
    @Transactional
    public SesionCajaDto cerrarForzado(UUID id, CierreForzadoRequest r) {
        jdbc.sql("select id from sesiones_caja where id = :id for update").param("id", id)
                .query((rs, i) -> rs.getObject(1, UUID.class)).optional()
                .orElseThrow(() -> ApiException.notFound("SESION_NO_ENCONTRADA", "Sesión de caja no encontrada"));
        Fila f = fila(id);
        if (!"ABIERTA".equals(f.estado())) {
            throw ApiException.conflict("SESION_YA_CERRADA", "La sesión de caja ya está cerrada");
        }
        ResumenCaja res = resumen(id, f.fondo());
        BigDecimal contado = r.efectivoContado() == null ? null : r.efectivoContado().setScale(2, RoundingMode.HALF_UP);
        BigDecimal diferencia = contado == null ? null : contado.subtract(res.efectivoEsperado());
        jdbc.sql("""
                update sesiones_caja set estado = 'CERRADA', cerrada_en = now(), efectivo_esperado = :e,
                       efectivo_contado = cast(:c as numeric), diferencia = cast(:d as numeric), observaciones = :o where id = :id
                """).param("e", res.efectivoEsperado()).param("c", contado).param("d", diferencia)
                .param("o", "CIERRE FORZADO POR ADMINISTRACIÓN: " + r.motivo().trim()).param("id", id).update();
        Map<String, Object> det = new LinkedHashMap<>();
        det.put("cajera", f.cajero());
        det.put("motivo", r.motivo().trim());
        det.put("esperado", res.efectivoEsperado());
        det.put("contado", contado);
        audit.registrar("CERRAR_CAJA_FORZADO", "sesiones_caja", id.toString(), det);
        return dto(fila(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<SesionCajaDto> listar(UUID cajeroId, String estado, LocalDate desde, LocalDate hasta, int page, int size) {
        int tam = PageResponse.limitarTamano(size);
        int pag = Math.max(page, 0);
        String est = estado == null || estado.isBlank() ? null : estado.trim().toUpperCase();
        String filtros = """
                where (cast(:c as uuid) is null or s.cajero_id = cast(:c as uuid))
                  and (cast(:e as text) is null or s.estado = cast(:e as text))
                  and (cast(:d as date) is null or s.abierta_en >= (cast(:d as date)::timestamp at time zone 'America/Bogota'))
                  and (cast(:h as date) is null or s.abierta_en < ((cast(:h as date) + 1)::timestamp at time zone 'America/Bogota'))
                """;
        long total = jdbc.sql("select count(*) from sesiones_caja s " + filtros).param("c", cajeroId).param("e", est)
                .param("d", desde).param("h", hasta).query(Long.class).single();
        List<SesionCajaDto> items = jdbc.sql(SELECT + filtros + " order by s.abierta_en desc limit :l offset :o")
                .param("c", cajeroId).param("e", est).param("d", desde).param("h", hasta).param("l", tam).param("o", pag * tam)
                .query((rs, i) -> mapear(rs)).list().stream().map(this::dto).toList();
        return new PageResponse<>(items, pag, tam, total);
    }
}
