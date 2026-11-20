package co.valery.pos;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

@TestPropertySource(properties = "app.rate-limit.auth-per-minute=5")
class RateLimitTest extends IntegrationTestBase {

    @Test
    void elLoginSeLimitaPorIpParaFrenarLaFuerzaBrutaSinAfectarElResto() throws Exception {
        int limitadas = 0;
        MvcResult ultima = null;
        for (int i = 0; i < 12; i++) {
            MvcResult r = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsString(Map.of("username", "nadie." + UUID.randomUUID().toString().substring(0, 6), "password", "x"))))
                    .andReturn();
            if (r.getResponse().getStatus() == 429) {
                limitadas++;
                ultima = r;
            }
        }
        assertThat(limitadas).isGreaterThanOrEqualTo(1);           // 12 intentos con un límite de 5 por minuto
        assertThat(ultima.getResponse().getHeader("Retry-After")).isNotBlank();
        assertThat(om.readTree(ultima.getResponse().getContentAsString()).get("code").asText()).isEqualTo("DEMASIADAS_PETICIONES");

        // El resto de la API y la salud no comparten ese contador
        get("/actuator/health", null, 200);
        get("/api/v1/productos", adminToken(), 200);
    }
}
