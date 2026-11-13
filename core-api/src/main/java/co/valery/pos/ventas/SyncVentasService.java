package co.valery.pos.ventas;

import co.valery.pos.auditoria.AuditService;
import co.valery.pos.common.ApiException;
import co.valery.pos.common.PageResponse;
import co.valery.pos.security.AuthUser;
import co.valery.pos.security.CurrentUser;
import co.valery.pos.ventas.VentaDtos.RechazoSyncDto;
import co.valery.pos.ventas.VentaDtos.SyncItemResultado;
import co.valery.pos.ventas.VentaDtos.SyncRequest;
import co.valery.pos.ventas.VentaDtos.SyncResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sincronización de ventas hechas sin conexión (RF-21, RF-22). Cada venta se procesa en su PROPIA transacción:
 * una rechazada nunca revierte a las demás. La idempotencia por clave evita duplicados ante reintentos.
 * Política de conflicto: si falta stock al sincronizar, la venta se RECHAZA, se conserva completa y se notifica.
 */
@Service
public class SyncVentasService {
    private static final Logger log = LoggerFactory.getLogger(SyncVentasService.class);

    private final VentaService ventas;
    private final JdbcClient jdbc;
    private final AuditService audit;
    private final ObjectMapper om;
    private final TransactionTemplate nueva;
    private final TransactionTemplate propia;

    public SyncVentasService(VentaService ventas, JdbcClient jdbc, AuditService audit, ObjectMapper om, PlatformTransactionManager tm) {
        this.ventas = ventas;
        this.jdbc = jdbc;
        this.audit = audit;
        this.om = om;
        this.nueva = new TransactionTemplate(tm);
        this.nueva.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.propia = new TransactionTemplate(tm);
    }

    public SyncResponse sincronizar(SyncRequest req) {
        AuthUser actor = CurrentUser.require();
        // Se procesan en el orden en que ocurrieron (hora del cliente solo para ordenar, nunca para sellar)
        List<VentaDtos.VentaRequest> orden = new ArrayList<>(req.ventas());
        orden.sort(Comparator.comparing(VentaDtos.VentaRequest::fechaCliente, Comparator.nullsLast(Comparator.<OffsetDateTime>naturalOrder())));

        List<SyncItemResultado> resultados = new ArrayList<>();
        int ok = 0, dup = 0, rech = 0;
        for (VentaDtos.VentaRequest v : orden) {
            try {
                VentaService.Resultado r = nueva.execute(st -> ventas.registrar(v, "OFFLINE_SYNC"));
                if (r == null) throw new IllegalStateException("Resultado vacío");
                resolverRechazoPrevio(v.idempotencyKey());
                if (r.duplicada()) {
                    dup++;
                    resultados.add(new SyncItemResultado(v.idempotencyKey(), "DUPLICADA", r.venta().id(), r.venta().numero(), null,
                            "La venta ya estaba registrada; no se duplicó"));
                } else {
                    ok++;
                    resultados.add(new SyncItemResultado(v.idempotencyKey(), "CONFIRMADA", r.venta().id(), r.venta().numero(), null, null));
                }
            } catch (DuplicateKeyException e) {
                // Carrera: la misma clave llegó por dos canales a la vez; ya existe
                dup++;
                resultados.add(new SyncItemResultado(v.idempotencyKey(), "DUPLICADA", null, null, null, "La venta ya estaba registrada"));
            } catch (ApiException e) {
                rech++;
                guardarRechazo(actor, v, e.code(), e.getMessage());
                resultados.add(new SyncItemResultado(v.idempotencyKey(), "RECHAZADA", null, null, e.code(), e.getMessage()));
            } catch (RuntimeException e) {
                log.error("Error inesperado sincronizando la venta {}", v.idempotencyKey(), e);
                rech++;
                guardarRechazo(actor, v, "ERROR_INTERNO", "Error interno al procesar la venta; se conservó para revisión");
                resultados.add(new SyncItemResultado(v.idempotencyKey(), "RECHAZADA", null, null, "ERROR_INTERNO",
                        "Error interno al procesar la venta; se conservó para revisión"));
            }
        }
        return new SyncResponse(ok, dup, rech, resultados);
    }

    private void guardarRechazo(AuthUser actor, VentaDtos.VentaRequest v, String codigo, String motivo) {
        String payload;
        try {
            payload = om.writeValueAsString(v);
        } catch (JsonProcessingException e) {
            payload = "{}";
        }
        final String payloadFinal = payload;
        propia.executeWithoutResult(st -> {
            jdbc.sql("""
                    insert into sync_rechazos(idempotency_key, cajero_id, motivo, detalle, payload, fecha_cliente)
                    values (:k, :c, :m, cast(:d as jsonb), cast(:p as jsonb), cast(:f as timestamptz))
                    on conflict (idempotency_key) do update set motivo = excluded.motivo, detalle = excluded.detalle,
                        payload = excluded.payload, recibido_en = now(), estado = 'RECHAZADA', resuelta_por = null,
                        resuelta_en = null, nota_resolucion = null
                    where sync_rechazos.cajero_id = excluded.cajero_id
                    """).param("k", v.idempotencyKey()).param("c", actor.id()).param("m", motivo)
                    .param("d", "{\"codigo\":\"" + codigo + "\"}").param("p", payloadFinal).param("f", v.fechaCliente()).update();
            audit.registrarComo(actor.id(), actor.username(), actor.rol().name(), "SYNC_VENTA_RECHAZADA", "sync_rechazos",
                    v.idempotencyKey().toString(), Map.of("codigo", codigo, "motivo", motivo), "DENEGADO");
        });
    }

    private void resolverRechazoPrevio(UUID key) {
        propia.executeWithoutResult(st -> jdbc.sql("""
                update sync_rechazos set estado = 'RESUELTA', resuelta_en = now(),
                       nota_resolucion = 'Reintento exitoso: la venta se registró correctamente'
                where idempotency_key = :k and estado = 'RECHAZADA'
                """).param("k", key).update());
    }

    // ---------- Consulta y resolución de rechazos ----------

    @Transactional(readOnly = true)
    public PageResponse<RechazoSyncDto> listarRechazos(String estado, int page, int size) {
        AuthUser actor = CurrentUser.require();
        UUID filtroCajero = actor.esAdmin() ? null : actor.id();
        int tam = PageResponse.limitarTamano(size);
        int pag = Math.max(page, 0);
        String est = estado == null || estado.isBlank() ? "RECHAZADA" : estado.trim().toUpperCase(Locale.ROOT);
        String filtros = """
                where (cast(:c as uuid) is null or r.cajero_id = cast(:c as uuid))
                  and (cast(:e as text) = 'TODAS' or r.estado = cast(:e as text))
                """;
        long total = jdbc.sql("select count(*) from sync_rechazos r " + filtros).param("c", filtroCajero).param("e", est)
                .query(Long.class).single();
        List<RechazoSyncDto> items = jdbc.sql("""
                select r.id, r.idempotency_key, r.cajero_id, u.nombre_completo as cajero, r.motivo, r.estado, r.fecha_cliente,
                       r.recibido_en, r.resuelta_en, r.nota_resolucion, r.payload::text as payload
                from sync_rechazos r join usuarios u on u.id = r.cajero_id
                """ + filtros + " order by r.recibido_en desc limit :l offset :o")
                .param("c", filtroCajero).param("e", est).param("l", tam).param("o", pag * tam)
                .query((rs, i) -> {
                    JsonNode payload;
                    try {
                        payload = om.readTree(rs.getString("payload"));
                    } catch (JsonProcessingException e) {
                        payload = om.createObjectNode();
                    }
                    return new RechazoSyncDto(rs.getObject("id", UUID.class), rs.getObject("idempotency_key", UUID.class),
                            rs.getObject("cajero_id", UUID.class), rs.getString("cajero"), rs.getString("motivo"), rs.getString("estado"),
                            rs.getObject("fecha_cliente", OffsetDateTime.class), rs.getObject("recibido_en", OffsetDateTime.class),
                            rs.getObject("resuelta_en", OffsetDateTime.class), rs.getString("nota_resolucion"), payload);
                }).list();
        return new PageResponse<>(items, pag, tam, total);
    }

    @Transactional
    public void resolver(UUID id, String nota) {
        AuthUser admin = CurrentUser.require();
        int n = jdbc.sql("""
                update sync_rechazos set estado = 'RESUELTA', resuelta_por = :a, resuelta_en = now(), nota_resolucion = :n
                where id = :id and estado = 'RECHAZADA'
                """).param("a", admin.id()).param("n", nota.trim()).param("id", id).update();
        if (n == 0) {
            if (jdbc.sql("select count(*) from sync_rechazos where id = :id").param("id", id).query(Long.class).single() == 0) {
                throw ApiException.notFound("RECHAZO_NO_ENCONTRADO", "Rechazo de sincronización no encontrado");
            }
            throw ApiException.conflict("RECHAZO_YA_RESUELTO", "El rechazo ya fue resuelto");
        }
        audit.registrar("RESOLVER_RECHAZO_SYNC", "sync_rechazos", id.toString(), Map.of("nota", nota.trim()));
    }
}
