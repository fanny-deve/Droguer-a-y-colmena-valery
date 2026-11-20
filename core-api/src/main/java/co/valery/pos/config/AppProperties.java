package co.valery.pos.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Jwt jwt, AdminBootstrap adminBootstrap, Analytics analytics, Redis redis, RateLimit rateLimit, Cors cors) {

    public record Jwt(String secret, int accessTtlMinutes, int refreshTtlDays) {}

    public record AdminBootstrap(String username, String password, String nombre) {}

    public record Analytics(String url, String apiKey) {}

    public record Redis(boolean enabled) {}

    /** Límites por IP y minuto: autenticación (anti fuerza bruta) y API en general. */
    public record RateLimit(int authPerMinute, int apiPerMinute) {}

    public record Cors(List<String> allowedOrigins) {}
}
