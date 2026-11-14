package co.valery.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

class ImportacionTest extends IntegrationTestBase {
    private static final String ADM = "/api/v1/admin";
    private static final String CAB = "codigo;nombre;categoria;unidad;precio_venta;costo;stock_minimo;punto_reorden;maneja_vencimiento;proveedor;stock_inicial;lote;fecha_vencimiento;costo_lote\n";

    private JsonNode subir(String ruta, String token, String nombre, byte[] bytes, int esperado) throws Exception {
        MockMultipartFile f = new MockMultipartFile("file", nombre, "text/csv", bytes);
        MvcResult r = mvc.perform(multipart(ADM + "/importaciones/" + ruta).file(f).header("Authorization", "Bearer " + token))
                .andExpect(status().is(esperado)).andReturn();
        String c = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return c.isBlank() ? null : om.readTree(c);
    }

    private String u() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    @Test
    void importaCatalogoConExistenciasInicialesYReportaLasFilasConError() throws Exception {
        String u = u();
        String csv = CAB
                + "IM" + u + "A;Jabón de Niño;Farmacia - Higiene y cuidado personal;UNIDAD;1.500,50;900;2;5;si;;10;LOTE-A;31/12/2030;900\n"
                + "IM" + u + "B;Miel pura 500g;Colmena - Insumos;FRASCO;22000;15000;;;no;;;;;\n"
                + "4006381333932;EAN malo;Farmacia - Medicamentos de venta libre;UNIDAD;1000;;;;;;;;;\n"
                + "IM" + u + "C;Sin categoría válida;Inventada;UNIDAD;1000;;;;;;;;;\n"
                + "IM" + u + "A;Código repetido;Cuidado del hogar;UNIDAD;1000;;;;;;;;;\n"
                + "IM" + u + "D;Con stock sin vencimiento;Cuidado del hogar;UNIDAD;1000;500;;;si;;5;;;\n";
        JsonNode r = subir("catalogo", adminToken(), "inventario inicial.csv", csv.getBytes(StandardCharsets.UTF_8), 200);
        assertThat(r.get("totalFilas").asInt()).isEqualTo(6);
        assertThat(r.get("filasOk").asInt()).isEqualTo(2);
        assertThat(r.get("filasError").asInt()).isEqualTo(4);
        assertThat(r.get("mensaje").asText()).contains("2 filas importadas y 4 rechazadas");

        // Cada error dice fila (como en Excel), campo y causa
        JsonNode errores = r.get("errores");
        assertThat(errores).hasSize(4);
        assertThat(errores.get(0).get("fila").asInt()).isEqualTo(4);
        assertThat(errores.get(0).get("campo").asText()).isEqualTo("codigo");
        assertThat(errores.get(0).get("motivo").asText()).contains("EAN-13");
        assertThat(errores.get(1).get("campo").asText()).isEqualTo("categoria");
        assertThat(errores.get(2).get("fila").asInt()).isEqualTo(6);
        assertThat(errores.get(2).get("campo").asText()).isEqualTo("codigo");
        assertThat(errores.get(3).get("campo").asText()).isEqualTo("fecha_vencimiento");

        // Lo válido quedó cargado completo: producto + lote inicial + kardex
        JsonNode lista = get(ADM + "/productos?q=IM" + u + "A", adminToken(), 200);
        assertThat(lista.get("items")).hasSize(1);
        JsonNode a = lista.get("items").get(0);
        assertThat(a.get("nombre").asText()).isEqualTo("Jabón de Niño");
        assertThat(a.get("precioVenta").decimalValue()).isEqualByComparingTo("1500.50");
        assertThat(a.get("stockVendible").decimalValue()).isEqualByComparingTo("10");
        JsonNode kardex = get(ADM + "/inventario/movimientos?productoId=" + a.get("id").asText() + "&tipo=CARGA_INICIAL", adminToken(), 200);
        assertThat(kardex.get("total").asLong()).isEqualTo(1);
        JsonNode b = get(ADM + "/productos?q=IM" + u + "B", adminToken(), 200).get("items").get(0);
        assertThat(b.get("stockVendible").decimalValue()).isEqualByComparingTo("0");
        assertThat(get(ADM + "/productos?q=IM" + u + "C", adminToken(), 200).get("items")).isEmpty();   // la fila con error NO dejó nada a medias
        assertThat(get(ADM + "/productos?q=IM" + u + "D", adminToken(), 200).get("items")).isEmpty();

        // Queda el historial de la importación con sus errores
        JsonNode hist = get(ADM + "/importaciones", adminToken(), 200);
        assertThat(hist.get("items").get(0).get("filasError").asInt()).isEqualTo(4);
        assertThat(get(ADM + "/importaciones/" + r.get("id").asText() + "/errores", adminToken(), 200)).hasSize(4);
        get(ADM + "/importaciones/" + UUID.randomUUID() + "/errores", adminToken(), 404);

        // Reimportar el mismo archivo no duplica: los códigos ya existen
        JsonNode otra = subir("catalogo", adminToken(), "inventario inicial.csv", csv.getBytes(StandardCharsets.UTF_8), 200);
        assertThat(otra.get("filasOk").asInt()).isZero();
    }

    @Test
    void aceptaArchivosDeExcelEnWindows1252() throws Exception {
        String u = u();
        String csv = CAB + "IM" + u + "W;Ñandú de peluche;Cuidado del hogar;UNIDAD;5000;;;;si;;;;;\n";
        JsonNode r = subir("catalogo", adminToken(), "excel.csv", csv.getBytes(Charset.forName("windows-1252")), 200);
        assertThat(r.get("filasOk").asInt()).isEqualTo(1);
        assertThat(get(ADM + "/productos?q=IM" + u + "W", adminToken(), 200).get("items").get(0).get("nombre").asText()).isEqualTo("Ñandú de peluche");
    }

    @Test
    void rechazaArchivosInvalidosYSoloLoUsaLaAdministradora() throws Exception {
        String sinColumna = "codigo;nombre;categoria\nX1;Algo;Cuidado del hogar\n";
        JsonNode e = subir("catalogo", adminToken(), "malo.csv", sinColumna.getBytes(StandardCharsets.UTF_8), 400);
        assertThat(e.get("code").asText()).isEqualTo("ARCHIVO_INVALIDO");
        assertThat(e.get("message").asText()).contains("precio_venta");
        assertThat(subir("catalogo", adminToken(), "vacio.csv", new byte[0], 400).get("code").asText()).isEqualTo("ARCHIVO_VACIO");
        assertThat(subir("catalogo", adminToken(), "soloencabezado.csv", CAB.getBytes(StandardCharsets.UTF_8), 400).get("code").asText())
                .isEqualTo("ARCHIVO_VACIO");
        String tk = tokenCajeraHabilitada(crearCajera());
        subir("catalogo", tk, "x.csv", CAB.getBytes(StandardCharsets.UTF_8), 403);
        subir("historico-ventas", tk, "x.csv", "codigo;fecha;unidades\n".getBytes(StandardCharsets.UTF_8), 403);
        get(ADM + "/importaciones", tk, 403);
    }

    @Test
    void importaElHistoricoDelCuadernoConUpsertYValidacionPorFila() throws Exception {
        String u = u();
        String codigo = "IH" + u;
        subir("catalogo", adminToken(), "p.csv", (CAB + codigo + ";Producto histórico;Cuidado del hogar;UNIDAD;2000;;;;si;;;;;\n").getBytes(StandardCharsets.UTF_8), 200);
        String id = get(ADM + "/productos?q=" + codigo, adminToken(), 200).get("items").get(0).get("id").asText();

        String manana = LocalDate.now().plusDays(3).toString();
        String csv = "codigo;fecha;unidades;ingresos\n"
                + codigo + ";2025-03-15;12;150.000\n"
                + codigo + ";16/03/2025;5;62500,50\n"
                + "NOEXISTE" + u + ";2025-03-15;3;\n"
                + codigo + ";" + manana + ";3;\n"
                + codigo + ";2025-03-17;-4;\n"
                + codigo + ";15 de marzo;4;\n";
        JsonNode r = subir("historico-ventas", adminToken(), "cuaderno.csv", csv.getBytes(StandardCharsets.UTF_8), 200);
        assertThat(r.get("filasOk").asInt()).isEqualTo(2);
        assertThat(r.get("filasError").asInt()).isEqualTo(4);
        assertThat(jdbc.sql("select count(*) from historico_ventas where producto_id = cast(:p as uuid)").param("p", id).query(Long.class).single()).isEqualTo(2L);
        assertThat(jdbc.sql("select sum(ingresos) from historico_ventas where producto_id = cast(:p as uuid)").param("p", id).query(BigDecimal.class).single())
                .isEqualByComparingTo("212500.50");

        // Volver a cargar una fecha corrige el dato (no duplica)
        subir("historico-ventas", adminToken(), "cuaderno.csv", ("codigo;fecha;unidades\n" + codigo + ";2025-03-15;20\n").getBytes(StandardCharsets.UTF_8), 200);
        assertThat(jdbc.sql("select count(*) from historico_ventas where producto_id = cast(:p as uuid)").param("p", id).query(Long.class).single()).isEqualTo(2L);
        assertThat(jdbc.sql("select unidades from historico_ventas where producto_id = cast(:p as uuid) and fecha = date '2025-03-15'").param("p", id)
                .query(BigDecimal.class).single()).isEqualByComparingTo("20");
        // El histórico alimenta la serie de demanda que usa la proyección
        assertThat(jdbc.sql("select coalesce(sum(unidades),0) from v_demanda_diaria where producto_id = cast(:p as uuid)").param("p", id)
                .query(BigDecimal.class).single()).isEqualByComparingTo("25");
    }

    @Test
    void ofreceLasPlantillasDeCarga() throws Exception {
        var r = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(ADM + "/importaciones/plantilla/catalogo")
                .header("Authorization", "Bearer " + adminToken())).andExpect(status().isOk()).andReturn();
        assertThat(r.getResponse().getContentType()).contains("text/csv");
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("codigo;nombre;categoria;unidad;precio_venta");
        get(ADM + "/importaciones/plantilla/otra", adminToken(), 404);
    }
}
