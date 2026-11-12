package co.valery.pos.usuarios;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RefreshTokenRepository {
    public record RefreshToken(UUID id, UUID usuarioId, UUID familiaId, OffsetDateTime expiraEn, OffsetDateTime revocadoEn) {}

    private final JdbcClient jdbc;

    public RefreshTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insertar(UUID usuarioId, UUID familiaId, String hash, int ttlDias, String ip, String userAgent) {
        return jdbc.sql("""
                insert into refresh_tokens(usuario_id, familia_id, token_hash, expira_en, ip, user_agent)
                values (:u, :f, :h, now() + make_interval(days => :d), cast(:ip as text), cast(:ua as text))
                returning id
                """).param("u", usuarioId).param("f", familiaId).param("h", hash).param("d", ttlDias)
                .param("ip", ip).param("ua", userAgent == null ? null : userAgent.substring(0, Math.min(userAgent.length(), 300)))
                .query((rs, i) -> rs.getObject(1, UUID.class)).single();
    }

    public Optional<RefreshToken> findByHash(String hash) {
        return jdbc.sql("select id, usuario_id, familia_id, expira_en, revocado_en from refresh_tokens where token_hash = :h for update")
                .param("h", hash)
                .query((rs, i) -> new RefreshToken(rs.getObject("id", UUID.class), rs.getObject("usuario_id", UUID.class),
                        rs.getObject("familia_id", UUID.class), rs.getObject("expira_en", OffsetDateTime.class),
                        rs.getObject("revocado_en", OffsetDateTime.class))).optional();
    }

    public void revocarYReemplazar(UUID id, UUID reemplazadoPor) {
        jdbc.sql("update refresh_tokens set revocado_en = now(), reemplazado_por = :r where id = :id")
                .param("id", id).param("r", reemplazadoPor).update();
    }

    public void revocarFamilia(UUID familiaId) {
        jdbc.sql("update refresh_tokens set revocado_en = now() where familia_id = :f and revocado_en is null")
                .param("f", familiaId).update();
    }

    public void revocarTodosDelUsuario(UUID usuarioId) {
        jdbc.sql("update refresh_tokens set revocado_en = now() where usuario_id = :u and revocado_en is null")
                .param("u", usuarioId).update();
    }
}
