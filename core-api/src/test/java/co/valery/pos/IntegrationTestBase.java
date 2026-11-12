package co.valery.pos;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base de pruebas: PostgreSQL real con las migraciones del proyecto y la aplicación conectada con el rol
 * `app_backend` (mínimos privilegios + RLS), igual que en producción.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTestBase {
    protected static final String ADMIN_USER = "admin.valery";
    protected static final String ADMIN_PASS_INICIAL = "Admin#Inicial2026";
    protected static final String ADMIN_PASS = "Admin#Definitiva2026";
    protected static final String CAJERA_PASS = "Cajera#Segura2026";

    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("pos").withUsername("postgres").withPassword("postgres");
    private static String adminTokenCache;

    static {
        PG.start();
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "postgres", "postgres");
             Statement st = c.createStatement()) {
            // Roles y esquema que Supabase trae de fábrica
            st.execute("create role anon nologin; create role authenticated nologin; create schema extensions;");
            List<Path> migraciones;
            try (Stream<Path> s = Files.list(Path.of("..", "database", "migrations"))) {
                migraciones = s.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().toList();
            }
            for (Path p : migraciones) {
                st.execute(Files.readString(p, StandardCharsets.UTF_8));
            }
            st.execute("alter role app_backend login password 'app_pw'");
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo preparar la base de datos de pruebas", e);
        }
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", () -> "app_backend");
        r.add("spring.datasource.password", () -> "app_pw");
        r.add("app.jwt.secret", () -> "secreto-de-pruebas-secreto-de-pruebas-0123456789");
        r.add("app.admin-bootstrap.username", () -> ADMIN_USER);
        r.add("app.admin-bootstrap.password", () -> ADMIN_PASS_INICIAL);
        r.add("app.admin-bootstrap.nombre", () -> "Administradora de Pruebas");
        r.add("app.cors.allowed-origins", () -> "http://localhost:5173");
        r.add("app.analytics.url", () -> "http://localhost:9");
        r.add("app.analytics.api-key", () -> "clave-prueba");
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper om;
    @Autowired protected JdbcClient jdbc;

    // ---------- Helpers HTTP ----------

    protected JsonNode enviar(MockHttpServletRequestBuilder req, String token, Object body, int esperado) throws Exception {
        if (token != null) req.header("Authorization", "Bearer " + token);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body));
        MvcResult r = mvc.perform(req).andExpect(status().is(esperado)).andReturn();
        String c = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return c.isBlank() ? null : om.readTree(c);
    }

    protected JsonNode post(String url, String token, Object body, int esperado) throws Exception {
        return enviar(MockMvcRequestBuilders.post(url), token, body, esperado);
    }

    protected JsonNode put(String url, String token, Object body, int esperado) throws Exception {
        return enviar(MockMvcRequestBuilders.put(url), token, body, esperado);
    }

    protected JsonNode get(String url, String token, int esperado) throws Exception {
        return enviar(MockMvcRequestBuilders.get(url), token, null, esperado);
    }

    // ---------- Helpers de dominio ----------

    protected JsonNode login(String username, String password, int esperado) throws Exception {
        return post("/api/v1/auth/login", null, java.util.Map.of("username", username, "password", password), esperado);
    }

    /** Token de la administradora ya habilitada (completa el cambio de contraseña inicial una sola vez). */
    protected synchronized String adminToken() throws Exception {
        if (adminTokenCache != null) return adminTokenCache;
        JsonNode l = login(ADMIN_USER, ADMIN_PASS_INICIAL, 200);
        if (l.get("debeCambiarPassword").asBoolean()) {
            l = post("/api/v1/auth/change-password", l.get("accessToken").asText(),
                    java.util.Map.of("passwordActual", ADMIN_PASS_INICIAL, "passwordNueva", ADMIN_PASS), 200);
        }
        adminTokenCache = l.get("accessToken").asText();
        return adminTokenCache;
    }

    protected record Cajera(UUID id, String username, String passwordTemporal) {}

    protected String usernameUnico(String prefijo) {
        return prefijo + "." + UUID.randomUUID().toString().substring(0, 8);
    }

    protected Cajera crearCajera() throws Exception {
        String username = usernameUnico("caja");
        JsonNode r = post("/api/v1/admin/usuarios", adminToken(), java.util.Map.of(
                "username", username, "nombreCompleto", "Cajera de Prueba"), 201);
        return new Cajera(UUID.fromString(r.get("usuario").get("id").asText()), username, r.get("passwordTemporal").asText());
    }

    /** Crea una cajera y completa su cambio de contraseña; devuelve su token operativo. */
    protected String tokenCajeraHabilitada(Cajera c) throws Exception {
        JsonNode l = login(c.username(), c.passwordTemporal(), 200);
        JsonNode n = post("/api/v1/auth/change-password", l.get("accessToken").asText(),
                java.util.Map.of("passwordActual", c.passwordTemporal(), "passwordNueva", CAJERA_PASS), 200);
        return n.get("accessToken").asText();
    }
}
