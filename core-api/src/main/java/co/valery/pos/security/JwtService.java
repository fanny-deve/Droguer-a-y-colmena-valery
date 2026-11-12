package co.valery.pos.security;

import co.valery.pos.common.Rol;
import co.valery.pos.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final SecretKey key;
    private final Duration accessTtl;

    public JwtService(AppProperties props) {
        byte[] secreto = props.jwt().secret() == null ? new byte[0] : props.jwt().secret().getBytes(StandardCharsets.UTF_8);
        if (secreto.length < 32) {
            throw new IllegalStateException("JWT_SECRET debe tener al menos 32 caracteres (RNF-08)");
        }
        this.key = Keys.hmacShaKeyFor(secreto);
        this.accessTtl = Duration.ofMinutes(props.jwt().accessTtlMinutes());
    }

    public String emitir(UUID usuarioId, String username, Rol rol, int tokenVersion) {
        Instant ahora = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(usuarioId.toString())
                .claim("usr", username)
                .claim("rol", rol.name())
                .claim("tv", tokenVersion)
                .issuedAt(Date.from(ahora))
                .expiration(Date.from(ahora.plus(accessTtl)))
                .signWith(key)
                .compact();
    }

    public Optional<Claims> validar(String token) {
        try {
            return Optional.of(Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload());
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public long ttlSegundos() {
        return accessTtl.toSeconds();
    }
}
