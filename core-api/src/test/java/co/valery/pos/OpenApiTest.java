package co.valery.pos;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

class OpenApiTest extends IntegrationTestBase {

    @Test
    void laDocumentacionOpenApiExponeLosEndpointsYElEsquemaDeSeguridadJwt() throws Exception {
        JsonNode doc = get("/v3/api-docs", null, 200);
        assertThat(doc.get("info").get("title").asText()).contains("Droguería & Colmena Valery");
        assertThat(doc.get("paths").has("/api/v1/ventas")).isTrue();
        assertThat(doc.get("paths").has("/api/v1/admin/usuarios")).isTrue();
        assertThat(doc.get("components").get("securitySchemes").get("bearerAuth").get("scheme").asText()).isEqualTo("bearer");
        assertThat(doc.get("paths").size()).isGreaterThan(60);
    }

    @Test
    void laSaludDeLaAplicacionEsPublicaPeroNadaMasLoEs() throws Exception {
        get("/actuator/health", null, 200);
        get("/actuator/env", null, 401);
        get("/api/v1/productos", null, 401);
        get("/ruta-inexistente", null, 401);
    }
}
