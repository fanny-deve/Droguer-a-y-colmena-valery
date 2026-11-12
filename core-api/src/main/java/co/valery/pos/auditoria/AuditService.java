package co.valery.pos.auditoria;

import co.valery.pos.common.PageResponse;
import co.valery.pos.security.AuthUser;
import co.valery.pos.security.CurrentUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Bitácora de auditoría (RF-20). Solo inserta: la tabla es inmutable por triggers y el rol de la aplicación
 * no tiene UPDATE ni DELETE sobre ella (RNF-09). La hora la fija PostgreSQL (RNF-14).
 */
@Service
public class AuditService {
    private final JdbcClient jdbc;
    private final ObjectMapper om;

    public AuditService(JdbcClient jdbc, ObjectMapper om) {
        this.jdbc = jdbc;
        this.om = om;
    }

    /** Registra en la misma transacción de la operación auditada: o se guardan ambas o ninguna. */
    public void registrar(String accion, String entidad, String entidadId, Map<String, ?> detalle) {
        AuthUser a = CurrentUser.opt().orElse(null);
        insertar(a == null ? null : a.id(), a == null ? null : a.username(), a == null ? null : a.rol().name(),
                accion, entidad, entidadId, detalle, "OK");
    }

    public void registrarComo(UUID usuarioId, String username, String rol, String accion, String entidad,
                              String entidadId, Map<String, ?> detalle, String resultado) {
        insertar(usuarioId, username, rol, accion, entidad, entidadId, detalle, resultado);
    }

    /** Intentos denegados: transacción propia para que persistan aunque la operación se revierta. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void denegado(AuthUser actor, String accion, String entidad, String entidadId, Map<String, ?> detalle) {
        insertar(actor == null ? null : actor.id(), actor == null ? null : actor.username(),
                actor == null ? null : actor.rol().name(), accion, entidad, entidadId, detalle, "DENEGADO");
    }

    private void insertar(UUID usuarioId, String username, String rol, String accion, String entidad,
                          String entidadId, Map<String, ?> detalle, String resultado) {
        jdbc.sql("""
                insert into auditoria(usuario_id, username, rol, accion, entidad, entidad_id, detalle, resultado, ip)
                values (cast(:usuarioId as uuid), cast(:username as text), cast(:rol as text), :accion, :entidad,
                        cast(:entidadId as text), cast(:detalle as jsonb), :resultado, cast(:ip as text))
                """)
                .param("usuarioId", usuarioId)
                .param("username", username)
                .param("rol", rol)
                .param("accion", accion)
                .param("entidad", entidad)
                .param("entidadId", entidadId)
                .param("detalle", json(detalle))
                .param("resultado", resultado)
                .param("ip", ipActual())
                .update();
    }

    private String json(Map<String, ?> detalle) {
        try {
            return om.writeValueAsString(detalle == null ? Map.of() : detalle);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private static String ipActual() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes sra) {
            return sra.getRequest().getRemoteAddr();
        }
        return null;
    }

    public record RegistroAuditoria(long id, UUID usuarioId, String username, String rol, String accion, String entidad,
                                    String entidadId, JsonNode detalle, String resultado, String ip,
                                    OffsetDateTime creadoEn) {}

    public record ResultadoVerificacion(boolean integra, long registrosVerificados, List<Map<String, Object>> fallas) {}

    @Transactional(readOnly = true)
    public PageResponse<RegistroAuditoria> listar(UUID usuarioId, String accion, String entidad, String resultado,
                                                  LocalDate desde, LocalDate hasta, int page, int size) {
        int tam = PageResponse.limitarTamano(size);
        int pag = Math.max(page, 0);
        String filtros = """
                where (cast(:usuarioId as uuid) is null or usuario_id = cast(:usuarioId as uuid))
                  and (cast(:accion as text) is null or accion = cast(:accion as text))
                  and (cast(:entidad as text) is null or entidad = cast(:entidad as text))
                  and (cast(:resultado as text) is null or resultado = cast(:resultado as text))
                  and (cast(:desde as date) is null or creado_en >= (cast(:desde as date)::timestamp at time zone 'America/Bogota'))
                  and (cast(:hasta as date) is null or creado_en < ((cast(:hasta as date) + 1)::timestamp at time zone 'America/Bogota'))
                """;
        long total = jdbc.sql("select count(*) from auditoria " + filtros)
                .param("usuarioId", usuarioId).param("accion", accion).param("entidad", entidad)
                .param("resultado", resultado).param("desde", desde).param("hasta", hasta)
                .query(Long.class).single();
        List<RegistroAuditoria> items = jdbc.sql("""
                select id, usuario_id, username, rol, accion, entidad, entidad_id, detalle::text as detalle,
                       resultado, ip, creado_en from auditoria
                """ + filtros + " order by id desc limit :limit offset :offset")
                .param("usuarioId", usuarioId).param("accion", accion).param("entidad", entidad)
                .param("resultado", resultado).param("desde", desde).param("hasta", hasta)
                .param("limit", tam).param("offset", pag * tam)
                .query((rs, i) -> {
                    JsonNode det;
                    try {
                        det = om.readTree(rs.getString("detalle"));
                    } catch (JsonProcessingException e) {
                        det = om.createObjectNode();
                    }
                    return new RegistroAuditoria(rs.getLong("id"), rs.getObject("usuario_id", UUID.class),
                            rs.getString("username"), rs.getString("rol"), rs.getString("accion"),
                            rs.getString("entidad"), rs.getString("entidad_id"), det, rs.getString("resultado"),
                            rs.getString("ip"), rs.getObject("creado_en", OffsetDateTime.class));
                }).list();
        return new PageResponse<>(items, pag, tam, total);
    }

    /** Recalcula la cadena de hashes SHA-256 y reporta cualquier alteración. */
    @Transactional(readOnly = true)
    public ResultadoVerificacion verificarIntegridad() {
        long total = jdbc.sql("select count(*) from auditoria").query(Long.class).single();
        List<Map<String, Object>> fallas = jdbc.sql("select id, motivo from fn_auditoria_verificar()")
                .query((rs, i) -> Map.<String, Object>of("id", rs.getLong("id"), "motivo", rs.getString("motivo")))
                .list();
        return new ResultadoVerificacion(fallas.isEmpty(), total, fallas);
    }
}
