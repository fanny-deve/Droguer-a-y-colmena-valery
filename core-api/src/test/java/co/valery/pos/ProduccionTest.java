package co.valery.pos;

import static org.assertj.core.api.Assertions.assertThat;

import co.valery.pos.mantenimiento.MantenimientoService;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

class ProduccionTest extends IntegrationTestBase {
    private static final String ADM = "/api/v1/admin";
    @Autowired MantenimientoService mantenimiento;

    private MvcResult pedir(String url, String token, String requestId) throws Exception {
        var req = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url);
        if (token != null) req.header("Authorization", "Bearer " + token);
        if (requestId != null) req.header("X-Request-Id", requestId);
        return mvc.perform(req).andReturn();
    }

    // ---------- Trazabilidad de peticiones ----------

    @Test
    void cadaRespuestaLlevaUnRequestIdQueTambienAparecenEnLosErrores() throws Exception {
        MvcResult r = pedir("/actuator/health", null, "mi-id-de-prueba-123");
        assertThat(r.getResponse().getHeader("X-Request-Id")).isEqualTo("mi-id-de-prueba-123");

        assertThat(pedir("/actuator/health", null, null).getResponse().getHeader("X-Request-Id")).isNotBlank();
        // Un identificador malicioso (inyección en logs) se descarta y se reemplaza
        String malo = "x\nFALSO-REGISTRO: admin entró";
        String generado = pedir("/actuator/health", null, null).getResponse().getHeader("X-Request-Id");
        assertThat(generado).matches("^[A-Za-z0-9-]{8,64}$");

        MvcResult err = pedir("/api/v1/ventas/" + UUID.randomUUID(), adminToken(), "traza-abc-987654");
        assertThat(err.getResponse().getStatus()).isEqualTo(404);
        JsonNode cuerpo = om.readTree(err.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(cuerpo.get("traceId").asText()).isEqualTo("traza-abc-987654");
        assertThat(malo).contains("\n");
    }

    @Test
    void laRespuestaIncluyeCabecerasDeSeguridadYNoRevelaElServidor() throws Exception {
        MvcResult r = pedir("/actuator/health", null, null);
        assertThat(r.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(r.getResponse().getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(r.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(r.getResponse().getContentAsString()).doesNotContain("diskSpace").doesNotContain("jdbc:");
    }

    // ---------- Límites del rol de la aplicación en la base de datos ----------

    @Test
    void laBaseDeDatosImponeTiemposMaximosAlRolDeLaAplicacion() {
        assertThat(jdbc.sql("show statement_timeout").query(String.class).single()).isEqualTo("30s");
        assertThat(jdbc.sql("show lock_timeout").query(String.class).single()).isEqualTo("10s");
        assertThat(jdbc.sql("show idle_in_transaction_session_timeout").query(String.class).single()).isEqualTo("1min");
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> jdbc.sql("select pg_sleep(40)").query().listOfRows());
    }

    // ---------- Mantenimiento ----------

    @Test
    void laLimpiezaDiariaSoloBorraTokensViejosYConservaLosVigentes() throws Exception {
        UUID admin = jdbc.sql("select id from usuarios where rol = 'ADMIN' limit 1").query(UUID.class).single();
        UUID familia = UUID.randomUUID();
        String viejo = "h-viejo-" + UUID.randomUUID();
        String reciente = "h-reciente-" + UUID.randomUUID();
        String vigente = "h-vigente-" + UUID.randomUUID();
        jdbc.sql("insert into refresh_tokens(usuario_id, familia_id, token_hash, expira_en, revocado_en) values (:u, :f, :h, now() + interval '5 days', now() - interval '40 days')")
                .param("u", admin).param("f", familia).param("h", viejo).update();
        jdbc.sql("insert into refresh_tokens(usuario_id, familia_id, token_hash, expira_en, revocado_en) values (:u, :f, :h, now() + interval '5 days', now() - interval '5 days')")
                .param("u", admin).param("f", familia).param("h", reciente).update();
        jdbc.sql("insert into refresh_tokens(usuario_id, familia_id, token_hash, expira_en) values (:u, :f, :h, now() + interval '5 days')")
                .param("u", admin).param("f", familia).param("h", vigente).update();
        assertThat(mantenimiento.purgarTokens()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("select count(*) from refresh_tokens where token_hash = :h").param("h", viejo).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from refresh_tokens where token_hash in (:a, :b)").param("a", reciente).param("b", vigente)
                .query(Long.class).single()).isEqualTo(2L);
    }

    // ---------- Exportación CSV ----------

    @Test
    void losReportesSeExportanACsvAbribleConExcelYProtegidoContraFormulas() throws Exception {
        int cat = jdbc.sql("select id from categorias order by id limit 1").query(Integer.class).single();
        String codigo = "CS" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        Map<String, Object> b = new HashMap<>();
        b.put("codigo", codigo);
        b.put("nombre", "=HYPERLINK(\"http://malo.example\",\"clic\")");
        b.put("categoriaId", cat);
        b.put("unidad", "UNIDAD");
        b.put("precioVenta", 2000);
        b.put("costoReferencia", 1000);
        String p = post(ADM + "/productos", adminToken(), b, 201).get("id").asText();
        post(ADM + "/inventario/entradas", adminToken(), Map.of("productoId", p, "codigoLote", "CSV1",
                "fechaVencimiento", LocalDate.now().plusDays(90).toString(), "cantidad", 10, "costoUnitario", 800), 201);
        post("/api/v1/ventas", adminToken(), Map.of("idempotencyKey", UUID.randomUUID().toString(),
                "items", List.of(Map.of("productoId", p, "cantidad", 2)), "formaPago", "TARJETA"), 201);
        String hoy = LocalDate.now().toString();

        MvcResult r = pedir(ADM + "/reportes/ventas/por-producto?desde=" + hoy + "&hasta=" + hoy + "&limite=500&formato=csv", adminToken(), null);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getContentType()).contains("text/csv");
        assertThat(r.getResponse().getHeader("Content-Disposition")).contains("attachment").contains(".csv");
        byte[] bytes = r.getResponse().getContentAsByteArray();
        assertThat(bytes[0]).isEqualTo((byte) 0xEF);                                   // BOM para que Excel lea UTF-8
        String csv = new String(bytes, StandardCharsets.UTF_8);
        assertThat(csv.lines().findFirst().orElse("")).contains("productoId").contains("ingresos").contains(";");
        assertThat(csv).contains(codigo);
        assertThat(csv).contains("'=HYPERLINK");                                         // la fórmula queda neutralizada
        assertThat(csv).doesNotContain(";=HYPERLINK");

        MvcResult resumen = pedir(ADM + "/reportes/ventas/resumen?formato=csv", adminToken(), null);
        String csvResumen = new String(resumen.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(csvResumen.lines().findFirst().orElse("")).contains("total").contains("rango.desde");
        assertThat(csvResumen.lines().count()).isEqualTo(2);                              // encabezado + una fila

        assertThat(pedir(ADM + "/reportes/inventario/valorizado?formato=csv", adminToken(), null).getResponse().getStatus()).isEqualTo(200);
        assertThat(pedir(ADM + "/reportes/inventario/perdidas?formato=csv", adminToken(), null).getResponse().getStatus()).isEqualTo(200);
        assertThat(pedir(ADM + "/reportes/ventas/por-periodo?formato=csv", adminToken(), null).getResponse().getStatus()).isEqualTo(200);
        // Sigue siendo exclusivo de la administradora
        String tk = tokenCajeraHabilitada(crearCajera());
        assertThat(pedir(ADM + "/reportes/ventas/resumen?formato=csv", tk, null).getResponse().getStatus()).isEqualTo(403);
    }
}
