package co.valery.pos;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProyeccionesTest extends IntegrationTestBase {
    private static final String ADM = "/api/v1/admin";

    private String producto(int min, int reorden) throws Exception {
        String codigo = "Y" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        int cat = jdbc.sql("select id from categorias order by id limit 1").query(Integer.class).single();
        Map<String, Object> b = new HashMap<>();
        b.put("codigo", codigo);
        b.put("nombre", "Producto " + codigo);
        b.put("categoriaId", cat);
        b.put("unidad", "UNIDAD");
        b.put("precioVenta", 3000);
        b.put("costoReferencia", 1500);
        b.put("stockMinimo", min);
        b.put("puntoReorden", reorden);
        return post(ADM + "/productos", adminToken(), b, 201).get("id").asText();
    }

    private void historia(String productoId, int semanas) {
        LocalDate hoy = LocalDate.now(ZoneId.of("America/Bogota"));
        for (int k = 1; k <= semanas; k++) {
            jdbc.sql("insert into historico_ventas(producto_id, fecha, unidades, ingresos) values (cast(:p as uuid), :f, 6, 18000)")
                    .param("p", productoId).param("f", hoy.minusDays(7L * k)).update();
        }
    }

    @Test
    void generaYConsultaProyeccionesUsandoElMicroservicioConSuApiKey() throws Exception {
        String p = producto(0, 0);
        historia(p, 12);

        JsonNode est = get(ADM + "/analitica/proyecciones/estado", adminToken(), 200);
        assertThat(est.get("disponible").asBoolean()).isTrue();

        JsonNode r = post(ADM + "/analitica/proyecciones/generar", adminToken(), Map.of("horizonteSemanas", 4, "productoIds", List.of(p)), 200);
        assertThat(r.get("productos").asInt()).isEqualTo(1);
        assertThat(r.get("periodos").asInt()).isEqualTo(4);
        assertThat(r.get("versionModelo").asText()).isEqualTo("stub-1.0");

        // Lo que el núcleo le envía al microservicio: la serie diaria del producto (contrato snake_case)
        assertThat(ultimaSolicitudAnalitica.get("horizonte_semanas").asInt()).isEqualTo(4);
        assertThat(ultimaSolicitudAnalitica.get("series")).hasSize(1);
        assertThat(ultimaSolicitudAnalitica.get("series").get(0).get("producto_id").asText()).isEqualTo(p);
        assertThat(ultimaSolicitudAnalitica.get("series").get(0).get("observaciones")).hasSize(12);

        JsonNode proy = get(ADM + "/analitica/proyecciones/" + p, adminToken(), 200);
        assertThat(proy.get("modelo").asText()).isEqualTo("STUB");
        assertThat(proy.get("periodos")).hasSize(4);
        assertThat(proy.get("periodos").get(0).get("demandaProyectada").decimalValue()).isEqualByComparingTo("7.5");
        assertThat(proy.get("demandaTotalHorizonte").decimalValue()).isEqualByComparingTo("30");
        assertThat(proy.get("errorWape").decimalValue()).isEqualByComparingTo("0.2");
        assertThat(get(ADM + "/analitica/proyecciones?size=100", adminToken(), 200).get("total").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(get(ADM + "/auditoria?accion=GENERAR_PROYECCIONES", adminToken(), 200).get("total").asLong()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void laSugerenciaDeReabastecimientoUsaLaProyeccionVigente() throws Exception {
        String p = producto(10, 20);
        historia(p, 10);
        post(ADM + "/inventario/entradas", adminToken(), Map.of("productoId", p, "codigoLote", "PY1",
                "fechaVencimiento", LocalDate.now().plusDays(200).toString(), "cantidad", 5, "costoUnitario", 1000), 201);
        post(ADM + "/analitica/proyecciones/generar", adminToken(), Map.of("horizonteSemanas", 4, "productoIds", List.of(p)), 200);

        JsonNode sug = null;
        for (JsonNode s : get(ADM + "/reabastecimiento/sugerencias?cobertura=4", adminToken(), 200).get("sugerencias")) {
            if (s.get("productoId").asText().equals(p)) sug = s;
        }
        assertThat(sug).isNotNull();
        assertThat(sug.get("fuenteDemanda").asText()).isEqualTo("PROYECCION");
        assertThat(sug.get("demandaSemanal").decimalValue()).isEqualByComparingTo("7.5");
    }

    @Test
    void siElMicroservicioFallaSoloSeAfectanLasProyeccionesYElRestoSigueOperando() throws Exception {
        String p = producto(0, 0);
        historia(p, 6);
        analiticaActiva = false;
        try {
            JsonNode e = post(ADM + "/analitica/proyecciones/generar", adminToken(), Map.of("productoIds", List.of(p)), 503);
            assertThat(e.get("code").asText()).isEqualTo("SERVICIO_ANALITICA_NO_DISPONIBLE");
            // El resto del sistema sigue operando con normalidad
            post(ADM + "/inventario/entradas", adminToken(), Map.of("productoId", p, "codigoLote", "FALLA1",
                    "fechaVencimiento", LocalDate.now().plusDays(90).toString(), "cantidad", 3, "costoUnitario", 1000), 201);
            post("/api/v1/ventas", adminToken(), Map.of("idempotencyKey", UUID.randomUUID().toString(),
                    "items", List.of(Map.of("productoId", p, "cantidad", 1)), "formaPago", "TARJETA"), 201);
            get(ADM + "/reabastecimiento/sugerencias", adminToken(), 200);
        } finally {
            analiticaActiva = true;
        }
    }

    @Test
    void sinHistorialNoSeProyectaYSoloLaAdministradoraAccede() throws Exception {
        String sinHistoria = producto(0, 0);
        assertThat(post(ADM + "/analitica/proyecciones/generar", adminToken(), Map.of("productoIds", List.of(sinHistoria)), 422)
                .get("code").asText()).isEqualTo("SIN_HISTORICO");
        get(ADM + "/analitica/proyecciones/" + sinHistoria, adminToken(), 404);
        String tk = tokenCajeraHabilitada(crearCajera());
        post(ADM + "/analitica/proyecciones/generar", tk, Map.of(), 403);
        get(ADM + "/analitica/proyecciones", tk, 403);
        get(ADM + "/analitica/proyecciones/estado", tk, 403);
    }
}
