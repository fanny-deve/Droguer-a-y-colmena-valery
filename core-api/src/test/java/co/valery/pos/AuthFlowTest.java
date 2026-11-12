package co.valery.pos;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuthFlowTest extends IntegrationTestBase {

    @Test
    void endpointsProtegidosSinTokenResponden401() throws Exception {
        get("/api/v1/auth/me", null, 401);
        get("/api/v1/admin/usuarios", null, 401);
        get("/api/v1/auth/me", "token.invalido.xyz", 401);
    }

    @Test
    void loginIncorrectoEsGenericoYQuedaAuditado() throws Exception {
        String u = usernameUnico("fantasma");
        JsonNode r = login(u, "cualquiera", 401);
        assertThat(r.get("code").asText()).isEqualTo("CREDENCIALES_INVALIDAS");
        Long n = jdbc.sql("select count(*) from auditoria where accion = 'LOGIN_FALLIDO' and username = :u")
                .param("u", u).query(Long.class).single();
        assertThat(n).isEqualTo(1L);
    }

    @Test
    void contrasenaTemporalObligaAcambiarlaAntesDeOperar() throws Exception {
        Cajera c = crearCajera();
        JsonNode l = login(c.username(), c.passwordTemporal(), 200);
        assertThat(l.get("debeCambiarPassword").asBoolean()).isTrue();
        String tk = l.get("accessToken").asText();
        // /me sí se permite; cualquier otra ruta no
        get("/api/v1/auth/me", tk, 200);
        JsonNode bloqueado = get("/api/v1/productos", tk, 403);
        assertThat(bloqueado.get("code").asText()).isEqualTo("CAMBIO_PASSWORD_REQUERIDO");
    }

    @Test
    void cambioDePasswordAplicaPoliticaYRevocaTokensAnteriores() throws Exception {
        Cajera c = crearCajera();
        JsonNode l = login(c.username(), c.passwordTemporal(), 200);
        String viejo = l.get("accessToken").asText();

        JsonNode debil = post("/api/v1/auth/change-password", viejo,
                Map.of("passwordActual", c.passwordTemporal(), "passwordNueva", "corta1"), 400);
        assertThat(debil.get("code").asText()).isEqualTo("PASSWORD_DEBIL");
        post("/api/v1/auth/change-password", viejo,
                Map.of("passwordActual", "incorrecta", "passwordNueva", CAJERA_PASS), 400);

        JsonNode ok = post("/api/v1/auth/change-password", viejo,
                Map.of("passwordActual", c.passwordTemporal(), "passwordNueva", CAJERA_PASS), 200);
        assertThat(ok.get("debeCambiarPassword").asBoolean()).isFalse();
        get("/api/v1/auth/me", viejo, 401);                         // token anterior ya no sirve
        get("/api/v1/auth/me", ok.get("accessToken").asText(), 200);
        login(c.username(), c.passwordTemporal(), 401);              // la temporal quedó anulada
        login(c.username(), CAJERA_PASS, 200);
    }

    @Test
    void cuentaSeBloqueaTrasCincoIntentosYLaAdministradoraLaDesbloquea() throws Exception {
        Cajera c = crearCajera();
        for (int i = 0; i < 4; i++) login(c.username(), "mala-" + i, 401);
        JsonNode quinto = login(c.username(), "mala-5", 423);
        assertThat(quinto.get("code").asText()).isEqualTo("CUENTA_BLOQUEADA");
        login(c.username(), c.passwordTemporal(), 423);              // ni con la clave correcta mientras esté bloqueada

        post("/api/v1/admin/usuarios/" + c.id() + "/desbloquear", adminToken(), null, 200);
        login(c.username(), c.passwordTemporal(), 200);
    }

    @Test
    void refreshRotaYDetectaReutilizacion() throws Exception {
        Cajera c = crearCajera();
        JsonNode l = login(c.username(), c.passwordTemporal(), 200);
        String r1 = l.get("refreshToken").asText();

        JsonNode n = post("/api/v1/auth/refresh", null, Map.of("refreshToken", r1), 200);
        String r2 = n.get("refreshToken").asText();
        assertThat(r2).isNotEqualTo(r1);

        post("/api/v1/auth/refresh", null, Map.of("refreshToken", r1), 401);   // reutilización detectada
        post("/api/v1/auth/refresh", null, Map.of("refreshToken", r2), 401);   // toda la familia quedó revocada
        Long n2 = jdbc.sql("select count(*) from auditoria where accion = 'REUSO_REFRESH_TOKEN' and entidad_id = :id")
                .param("id", c.id().toString()).query(Long.class).single();
        assertThat(n2).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void logoutRevocaLaSesion() throws Exception {
        Cajera c = crearCajera();
        JsonNode l = login(c.username(), c.passwordTemporal(), 200);
        post("/api/v1/auth/logout", l.get("accessToken").asText(), Map.of("refreshToken", l.get("refreshToken").asText()), 204);
        post("/api/v1/auth/refresh", null, Map.of("refreshToken", l.get("refreshToken").asText()), 401);
    }

    @Test
    void meDevuelvePermisosSegunElRol() throws Exception {
        JsonNode admin = get("/api/v1/auth/me", adminToken(), 200);
        assertThat(admin.get("usuario").get("rol").asText()).isEqualTo("ADMIN");
        assertThat(admin.get("permisos").toString()).contains("USUARIOS_GESTIONAR", "COSTOS_VER");

        String tk = tokenCajeraHabilitada(crearCajera());
        JsonNode caj = get("/api/v1/auth/me", tk, 200);
        assertThat(caj.get("usuario").get("rol").asText()).isEqualTo("CAJERO");
        assertThat(caj.get("permisos").toString()).contains("VENTAS_REGISTRAR");
        assertThat(caj.get("permisos").toString()).doesNotContain("COSTOS_VER", "USUARIOS_GESTIONAR", "REPORTES_VER");
    }
}
