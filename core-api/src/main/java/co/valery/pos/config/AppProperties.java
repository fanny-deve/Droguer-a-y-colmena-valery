package co.valery.pos.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Jwt jwt, AdminBootstrap adminBootstrap, Analytics analytics, Cors cors) {

    public record Jwt(String secret, int accessTtlMinutes, int refreshTtlDays) {}

    public record AdminBootstrap(String username, String password, String nombre) {}

    public record Analytics(String url, String apiKey) {}

    public record Cors(List<String> allowedOrigins) {}
}
