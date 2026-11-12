package co.valery.pos;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UsuariosAdminTest extends IntegrationTestBase {

    @Test
    void administradoraCreaCajeraConContrasenaTemporalYValidaDatos() throws Exception {
        String username = usernameUnico("nueva");
        JsonNode r = post("/api/v1/admin/usuarios", adminToken(), Map.of(
                "username", username, "nombreCompleto", "María Pérez", "telefono", "3001234567"), 201);
        assertThat(r.get("usuario").get("rol").asText()).isEqualTo("CAJERO");
        assertThat(r.get("usuario").get("debeCambiarPassword").asBoolean()).isTrue();
        assertThat(r.get("passwordTemporal").asText()).hasSize(14);
        assertThat(r.toString()).doesNotContain("password_hash", "passwordHash");

        post("/api/v1/admin/usuarios", adminToken(), Map.of("username", username, "nombreCompleto", "Otra Persona"), 409);
        post("/api/v1/admin/usuarios", adminToken(), Map.of("username", "ab", "nombreCompleto", "Nombre Válido"), 400);
        post("/api/v1/admin/usuarios", adminToken(), Map.of("username", usernameUnico("x"), "nombreCompleto", "Nombre Válido",
                "email", "no-es-correo"), 400);
    }

    @Test
    void cajeraNoPuedeAccederAFuncionesDeAdministracion() throws Exception {
        String tk = tokenCajeraHabilitada(crearCajera());
        get("/api/v1/admin/usuarios", tk, 403);
        get("/api/v1/admin/auditoria", tk, 403);
        get("/api/v1/admin/auditoria/verificar", tk, 403);
        post("/api/v1/admin/usuarios", tk, Map.of("username", usernameUnico("hack"), "nombreCompleto", "Intento Indebido"), 403);
        post("/api/v1/admin/usuarios/" + UUID.randomUUID() + "/desactivar", tk, Map.of("motivo", "intento indebido"), 403);

        // Los intentos denegados quedan registrados y visibles para la administradora
        JsonNode aud = get("/api/v1/admin/auditoria?accion=ACCESO_DENEGADO&resultado=DENEGADO&size=100", adminToken(), 200);
        assertThat(aud.get("total").asLong()).isGreaterThanOrEqualTo(5);
    }

    @Test
    void desactivarInvalidaDeInmediatoElTokenVigenteYReactivarEntregaNuevaTemporal() throws Exception {
        Cajera c = crearCajera();
        String tk = tokenCajeraHabilitada(c);
        get("/api/v1/auth/me", tk, 200);

        post("/api/v1/admin/usuarios/" + c.id() + "/desactivar", adminToken(), Map.of("motivo", "Fin de contrato"), 200);
        get("/api/v1/auth/me", tk, 401);                 // el JWT sigue sin vencer, pero la cuenta ya no vale
        login(c.username(), CAJERA_PASS, 401);
        post("/api/v1/admin/usuarios/" + c.id() + "/desactivar", adminToken(), Map.of("motivo", "Fin de contrato"), 409);

        JsonNode re = post("/api/v1/admin/usuarios/" + c.id() + "/reactivar", adminToken(), null, 200);
        assertThat(re.get("usuario").get("estado").asText()).isEqualTo("ACTIVO");
        JsonNode l = login(c.username(), re.get("passwordTemporal").asText(), 200);
        assertThat(l.get("debeCambiarPassword").asBoolean()).isTrue();
    }

    @Test
    void desactivarExigeMotivoYCajaCerrada() throws Exception {
        Cajera c = crearCajera();
        post("/api/v1/admin/usuarios/" + c.id() + "/desactivar", adminToken(), Map.of("motivo", "no"), 400);

        jdbc.sql("insert into sesiones_caja(cajero_id, fondo_inicial) values (:id, 50000)").param("id", c.id()).update();
        JsonNode e = post("/api/v1/admin/usuarios/" + c.id() + "/desactivar", adminToken(), Map.of("motivo", "Fin de contrato"), 409);
        assertThat(e.get("code").asText()).isEqualTo("CAJA_ABIERTA");

        jdbc.sql("update sesiones_caja set estado = 'CERRADA', cerrada_en = now() where cajero_id = :id")
                .param("id", c.id()).update();
        post("/api/v1/admin/usuarios/" + c.id() + "/desactivar", adminToken(), Map.of("motivo", "Fin de contrato"), 200);
    }

    @Test
    void resetPasswordRevocaSesionesYObligaCambio() throws Exception {
        Cajera c = crearCajera();
        String tk = tokenCajeraHabilitada(c);
        JsonNode r = post("/api/v1/admin/usuarios/" + c.id() + "/reset-password", adminToken(), null, 200);
        get("/api/v1/auth/me", tk, 401);
        login(c.username(), CAJERA_PASS, 401);
        JsonNode l = login(c.username(), r.get("passwordTemporal").asText(), 200);
        assertThat(l.get("debeCambiarPassword").asBoolean()).isTrue();
    }

    @Test
    void eliminarExigeReautenticacionMotivoYEsIrreversible() throws Exception {
        Cajera c = crearCajera();
        String tk = tokenCajeraHabilitada(c);
        String url = "/api/v1/admin/usuarios/" + c.id() + "/eliminar";

        JsonNode mal = post(url, adminToken(), Map.of("motivo", "Retiro definitivo", "passwordAdmin", "incorrecta"), 403);
        assertThat(mal.get("code").asText()).isEqualTo("PASSWORD_INCORRECTA");
        get("/api/v1/auth/me", tk, 200);                 // sigue activa: nada cambió

        JsonNode ok = post(url, adminToken(), Map.of("motivo", "Retiro definitivo", "passwordAdmin", ADMIN_PASS), 200);
        assertThat(ok.get("estado").asText()).isEqualTo("ELIMINADO");
        get("/api/v1/auth/me", tk, 401);
        login(c.username(), CAJERA_PASS, 401);
        assertThat(jdbc.sql("select email is null and telefono is null from usuarios where id = :id").param("id", c.id())
                .query(Boolean.class).single()).isTrue();

        // Irreversible y con identidad reservada
        post(url, adminToken(), Map.of("motivo", "Retiro definitivo", "passwordAdmin", ADMIN_PASS), 409);
        post("/api/v1/admin/usuarios/" + c.id() + "/reactivar", adminToken(), null, 409);
        post("/api/v1/admin/usuarios", adminToken(), Map.of("username", c.username(), "nombreCompleto", "Reutiliza Nombre"), 409);

        // La trazabilidad se conserva
        Long n = jdbc.sql("select count(*) from auditoria where accion = 'ELIMINAR_USUARIO' and entidad_id = :id")
                .param("id", c.id().toString()).query(Long.class).single();
        assertThat(n).isEqualTo(1L);
    }

    @Test
    void lasCuentasDeAdministradoraNoSeGestionanPorEstosEndpoints() throws Exception {
        UUID adminId = jdbc.sql("select id from usuarios where rol = 'ADMIN' limit 1").query(UUID.class).single();
        get("/api/v1/admin/usuarios/" + adminId, adminToken(), 404);
        post("/api/v1/admin/usuarios/" + adminId + "/desactivar", adminToken(), Map.of("motivo", "intento indebido"), 404);
        JsonNode lista = get("/api/v1/admin/usuarios?size=200", adminToken(), 200);
        for (JsonNode u : lista.get("items")) assertThat(u.get("rol").asText()).isEqualTo("CAJERO");
    }

    @Test
    void laBitacoraEsInmutableYSuCadenaDeHashesEsIntegra() throws Exception {
        crearCajera();
        JsonNode v = get("/api/v1/admin/auditoria/verificar", adminToken(), 200);
        assertThat(v.get("integra").asBoolean()).isTrue();
        assertThat(v.get("registrosVerificados").asLong()).isPositive();

        // Ni siquiera con acceso directo a la base de datos con el rol de la aplicación se puede alterar
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> jdbc.sql("update auditoria set accion = 'X'").update());
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> jdbc.sql("delete from auditoria").update());
    }

    @Test
    void elRolDeLaAplicacionNoPuedeBorrarUsuariosNiTablasDeTrazabilidad() {
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> jdbc.sql("delete from usuarios").update());
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> jdbc.sql("delete from ventas").update());
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> jdbc.sql("delete from movimientos_inventario").update());
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> jdbc.sql("update movimientos_inventario set motivo = 'x'").update());
    }
}
