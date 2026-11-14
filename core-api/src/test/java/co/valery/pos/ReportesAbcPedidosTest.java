package co.valery.pos;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReportesAbcPedidosTest extends IntegrationTestBase {
    private static final String ADM = "/api/v1/admin";

    private String fecha(int dias) {
        return LocalDate.now(ZoneId.of("America/Bogota")).plusDays(dias).toString();
    }

    private String producto(int precio, int min, int reorden, String proveedorId) throws Exception {
        String codigo = "R" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        int cat = jdbc.sql("select id from categorias order by id limit 1").query(Integer.class).single();
        Map<String, Object> b = new HashMap<>();
        b.put("codigo", codigo);
        b.put("nombre", "Producto " + codigo);
        b.put("categoriaId", cat);
        b.put("unidad", "UNIDAD");
        b.put("precioVenta", precio);
        b.put("costoReferencia", precio / 2);
        b.put("stockMinimo", min);
        b.put("puntoReorden", reorden);
        if (proveedorId != null) b.put("proveedorPrincipalId", proveedorId);
        return post(ADM + "/productos", adminToken(), b, 201).get("id").asText();
    }

    private String lote(String productoId, String codigo, int cantidad) throws Exception {
        return post(ADM + "/inventario/entradas", adminToken(), Map.of("productoId", productoId, "codigoLote", codigo,
                "fechaVencimiento", fecha(120), "cantidad", cantidad, "costoUnitario", 1000), 201).get("id").asText();
    }

    private JsonNode vender(String tk, String productoId, int cantidad, String forma, Object recibido) throws Exception {
        Map<String, Object> b = new HashMap<>();
        b.put("idempotencyKey", UUID.randomUUID().toString());
        b.put("items", List.of(Map.of("productoId", productoId, "cantidad", cantidad)));
        b.put("formaPago", forma);
        if (recibido != null) b.put("montoRecibido", recibido);
        return post("/api/v1/ventas", tk, b, 201);
    }

    private String proveedor(int diasEntrega) throws Exception {
        return post(ADM + "/proveedores", adminToken(), Map.of("nombre", "Prov " + UUID.randomUUID().toString().substring(0, 8),
                "tiempoEntregaDias", diasEntrega), 201).get("id").asText();
    }

    private BigDecimal d(JsonNode n, String campo) {
        return n.get(campo).decimalValue();
    }

    // ------------------------------------------------------------------ reportes

    @Test
    void reportesDeVentasCuadranConLasVentasRegistradasYSoloLosVeLaAdministradora() throws Exception {
        String hoy = fecha(0);
        String q = "?desde=" + hoy + "&hasta=" + hoy;
        JsonNode antes = get(ADM + "/reportes/ventas/resumen" + q, adminToken(), 200);
        JsonNode antesPago = get(ADM + "/reportes/ventas/por-forma-pago" + q, adminToken(), 200);

        String p = producto(3000, 0, 0, null);
        lote(p, "RP1", 100);
        vender(adminToken(), p, 4, "EFECTIVO", 20000);
        vender(adminToken(), p, 2, "TARJETA", null);
        JsonNode anulable = vender(adminToken(), p, 1, "TARJETA", null);
        post(ADM + "/ventas/" + anulable.get("id").asText() + "/anular", adminToken(), Map.of("motivo", "Prueba de reportes"), 200);

        JsonNode despues = get(ADM + "/reportes/ventas/resumen" + q, adminToken(), 200);
        assertThat(d(despues, "total").subtract(d(antes, "total"))).isEqualByComparingTo("18000");
        assertThat(d(despues, "costo").subtract(d(antes, "costo"))).isEqualByComparingTo("6000");
        assertThat(d(despues, "margenBruto").subtract(d(antes, "margenBruto"))).isEqualByComparingTo("12000");
        assertThat(despues.get("ventas").asLong() - antes.get("ventas").asLong()).isEqualTo(2);
        assertThat(despues.get("anuladas").asLong() - antes.get("anuladas").asLong()).isEqualTo(1);

        BigDecimal efectivoAntes = BigDecimal.ZERO;
        for (JsonNode f : antesPago) if (f.get("formaPago").asText().equals("EFECTIVO")) efectivoAntes = f.get("total").decimalValue();
        BigDecimal efectivoDespues = BigDecimal.ZERO;
        for (JsonNode f : get(ADM + "/reportes/ventas/por-forma-pago" + q, adminToken(), 200)) {
            if (f.get("formaPago").asText().equals("EFECTIVO")) efectivoDespues = f.get("total").decimalValue();
        }
        assertThat(efectivoDespues.subtract(efectivoAntes)).isEqualByComparingTo("12000");

        JsonNode porProducto = get(ADM + "/reportes/ventas/por-producto" + q + "&limite=500", adminToken(), 200);
        JsonNode fila = null;
        for (JsonNode f : porProducto) if (f.get("productoId").asText().equals(p)) fila = f;
        assertThat(fila).isNotNull();
        assertThat(d(fila, "unidades")).isEqualByComparingTo("6");
        assertThat(d(fila, "ingresos")).isEqualByComparingTo("18000");
        assertThat(d(fila, "margen")).isEqualByComparingTo("12000");

        assertThat(get(ADM + "/reportes/ventas/por-categoria" + q, adminToken(), 200)).isNotEmpty();
        JsonNode serie = get(ADM + "/reportes/ventas/por-periodo" + q + "&agrupacion=DIA", adminToken(), 200);
        assertThat(serie).hasSize(1);
        assertThat(serie.get(0).get("periodo").asText()).isEqualTo(hoy);
        get(ADM + "/reportes/ventas/por-periodo" + q + "&agrupacion=SEMANA", adminToken(), 200);
        get(ADM + "/reportes/ventas/por-periodo" + q + "&agrupacion=MES", adminToken(), 200);

        assertThat(get(ADM + "/reportes/ventas/resumen?desde=" + fecha(5) + "&hasta=" + fecha(1), adminToken(), 400).get("code").asText())
                .isEqualTo("RANGO_INVALIDO");
        get(ADM + "/reportes/ventas/por-periodo" + q + "&agrupacion=SIGLO", adminToken(), 400);
        String tk = tokenCajeraHabilitada(crearCajera());
        get(ADM + "/reportes/ventas/resumen", tk, 403);
        get(ADM + "/reportes/inventario/valorizado", tk, 403);
    }

    @Test
    void reportesDeInventarioValoranExistenciasMovimientosYPerdidas() throws Exception {
        JsonNode valAntes = get(ADM + "/reportes/inventario/valorizado", adminToken(), 200);
        JsonNode perdAntes = get(ADM + "/reportes/inventario/perdidas", adminToken(), 200);

        String p = producto(3000, 0, 0, null);
        String lote = lote(p, "RI1", 10);                            // 10 × 1.000 = 10.000 vendible
        jdbc.sql("""
                insert into lotes(producto_id, codigo_lote, fecha_vencimiento, cantidad_inicial, cantidad_disponible, costo_unitario)
                values (cast(:p as uuid), 'RIV', current_date - 2, 5, 5, 1000)
                """).param("p", p).update();                          // 5 × 1.000 = 5.000 vencido
        post(ADM + "/inventario/ajustes", adminToken(), Map.of("loteId", lote, "tipo", "DANO", "sentido", "NEGATIVO",
                "cantidad", 3, "motivo", "Envase roto en bodega"), 201);

        JsonNode val = get(ADM + "/reportes/inventario/valorizado", adminToken(), 200);
        assertThat(d(val, "valorVendible").subtract(d(valAntes, "valorVendible"))).isEqualByComparingTo("7000");   // 10 - 3 dañadas
        assertThat(d(val, "valorVencido").subtract(d(valAntes, "valorVencido"))).isEqualByComparingTo("5000");

        JsonNode perd = get(ADM + "/reportes/inventario/perdidas?desde=" + fecha(0) + "&hasta=" + fecha(0), adminToken(), 200);
        assertThat(d(perd, "totalPerdido")).isGreaterThanOrEqualTo(new BigDecimal("3000"));
        assertThat(d(perd, "vencidoPendienteValor").subtract(d(perdAntes, "vencidoPendienteValor"))).isEqualByComparingTo("5000");

        JsonNode movs = get(ADM + "/reportes/inventario/movimientos?desde=" + fecha(0) + "&hasta=" + fecha(0), adminToken(), 200);
        boolean hayEntradas = false;
        for (JsonNode m : movs) if (m.get("tipo").asText().equals("ENTRADA")) hayEntradas = true;
        assertThat(hayEntradas).isTrue();
    }

    // ------------------------------------------------------------------ ABC

    @Test
    void clasificacionAbcAsignaCategoriasPorValorYSeConsultaConSuFecha() throws Exception {
        String grande = producto(100000, 0, 0, null);
        String chico = producto(1000, 0, 0, null);
        String sinVentas = producto(1000, 0, 0, null);
        lote(grande, "AB1", 600);
        lote(chico, "AB2", 5);
        lote(sinVentas, "AB3", 5);
        vender(adminToken(), grande, 500, "TARJETA", null);          // 50.000.000: domina el consumo
        vender(adminToken(), chico, 1, "TARJETA", null);

        JsonNode r = post(ADM + "/analitica/abc/calcular", adminToken(), null, 200);
        assertThat(r.get("productos").asInt()).isGreaterThanOrEqualTo(3);
        assertThat(r.get("porCategoria").get("A").asInt()).isGreaterThanOrEqualTo(1);

        assertThat(get(ADM + "/productos/" + grande, adminToken(), 200).get("clasificacionAbc").asText()).isEqualTo("A");
        assertThat(get(ADM + "/productos/" + chico, adminToken(), 200).get("clasificacionAbc").asText()).isEqualTo("C");
        assertThat(get(ADM + "/productos/" + sinVentas, adminToken(), 200).get("clasificacionAbc").asText()).isEqualTo("C");

        JsonNode a = get(ADM + "/analitica/abc?categoria=A&size=100", adminToken(), 200);
        JsonNode fila = null;
        for (JsonNode f : a.get("items")) if (f.get("productoId").asText().equals(grande)) fila = f;
        assertThat(fila).isNotNull();
        assertThat(fila.get("calculadoEn").asText()).isNotBlank();
        assertThat(d(fila, "participacion")).isGreaterThan(new BigDecimal("90"));

        JsonNode resumen = get(ADM + "/analitica/abc/resumen", adminToken(), 200);
        BigDecimal suma = BigDecimal.ZERO;
        for (JsonNode f : resumen) suma = suma.add(f.get("participacion").decimalValue());
        assertThat(suma).isBetween(new BigDecimal("99.9"), new BigDecimal("100.1"));
        assertThat(get(ADM + "/productos?abc=A&size=100", adminToken(), 200).get("total").asLong()).isGreaterThanOrEqualTo(1);
        post(ADM + "/analitica/abc/calcular", tokenCajeraHabilitada(crearCajera()), null, 403);
    }

    // ------------------------------------------------------------------ pedidos y reabastecimiento

    private JsonNode sugerenciaDe(String productoId) throws Exception {
        JsonNode r = get(ADM + "/reabastecimiento/sugerencias?cobertura=4", adminToken(), 200);
        for (JsonNode s : r.get("sugerencias")) if (s.get("productoId").asText().equals(productoId)) return s;
        return null;
    }

    @Test
    void sugerenciaEditableCreaPedidoQueSeEnviaYSeRecibeConTrazabilidad() throws Exception {
        String prov = proveedor(7);
        String p = producto(3000, 10, 20, prov);
        lote(p, "PD0", 9);
        vender(adminToken(), p, 4, "TARJETA", null);                 // quedan 5 y hay historial de demanda

        JsonNode sug = sugerenciaDe(p);
        assertThat(sug).isNotNull();
        assertThat(sug.get("fuenteDemanda").asText()).isEqualTo("HISTORICO");
        assertThat(d(sug, "demandaSemanal")).isEqualByComparingTo("0.5");
        assertThat(d(sug, "cantidadSugerida")).isEqualByComparingTo("18");        // 0,5 × 5 semanas + 20 − 5 existencias
        assertThat(sug.get("motivos").toString()).contains("stock mínimo");
        assertThat(get(ADM + "/reabastecimiento/sugerencias", adminToken(), 200).get("aviso").asText()).contains("no realiza compras");

        // La administradora modifica la cantidad sugerida antes de crear el pedido
        Map<String, Object> req = Map.of("proveedorId", prov, "notas", "Pedido semanal",
                "items", List.of(Map.of("productoId", p, "cantidad", 25, "sugerido", true)));
        JsonNode pedido = post(ADM + "/pedidos", adminToken(), req, 201);
        String id = pedido.get("id").asText();
        assertThat(pedido.get("estado").asText()).isEqualTo("BORRADOR");
        assertThat(pedido.get("items").get(0).get("sugerido").asBoolean()).isTrue();
        assertThat(d(pedido, "totalEstimado")).isEqualByComparingTo("25000");      // 25 × último costo de compra (1.000)
        assertThat(sugerenciaDe(p)).isNotNull();                                   // un borrador aún no cuenta como "en tránsito"

        put(ADM + "/pedidos/" + id, adminToken(), Map.of("proveedorId", prov, "items",
                List.of(Map.of("productoId", p, "cantidad", 25, "costoUnitarioEstimado", 1200))), 200);
        post(ADM + "/pedidos/" + id + "/enviar", adminToken(), null, 200);
        assertThat(sugerenciaDe(p)).isNull();                                      // lo pedido ya cubre la necesidad
        assertThat(put(ADM + "/pedidos/" + id, adminToken(), Map.of("proveedorId", prov, "items",
                List.of(Map.of("productoId", p, "cantidad", 5))), 409).get("code").asText()).isEqualTo("PEDIDO_NO_EDITABLE");
        assertThat(post(ADM + "/proveedores/" + prov + "/desactivar", adminToken(), null, 409).get("code").asText())
                .isEqualTo("PROVEEDOR_CON_PEDIDOS");

        // Recepción parcial
        String otro = producto(1000, 0, 0, null);
        assertThat(post(ADM + "/pedidos/" + id + "/recepcion", adminToken(), Map.of("items", List.of(Map.of("productoId", otro,
                "codigoLote", "X1", "fechaVencimiento", fecha(100), "cantidad", 1, "costoUnitario", 900))), 422).get("code").asText())
                .isEqualTo("PRODUCTO_FUERA_DE_PEDIDO");
        JsonNode parcial = post(ADM + "/pedidos/" + id + "/recepcion", adminToken(), Map.of("items", List.of(Map.of("productoId", p,
                "codigoLote", "PD1", "fechaVencimiento", fecha(100), "cantidad", 10, "costoUnitario", 1200))), 200);
        assertThat(parcial.get("estado").asText()).isEqualTo("RECIBIDO_PARCIAL");
        assertThat(d(parcial.get("items").get(0), "pendiente")).isEqualByComparingTo("15");

        // Trazabilidad punta a punta: lote ↔ pedido ↔ proveedor ↔ kardex
        Map<String, Object> traza = jdbc.sql("select pedido_id::text as pedido, proveedor_id::text as prov from lotes where producto_id = cast(:p as uuid) and codigo_lote = 'PD1'")
                .param("p", p).query((rs, i) -> Map.<String, Object>of("pedido", rs.getString("pedido"), "prov", rs.getString("prov"))).single();
        assertThat(traza.get("pedido")).isEqualTo(id);
        assertThat(traza.get("prov")).isEqualTo(prov);

        JsonNode total = post(ADM + "/pedidos/" + id + "/recepcion", adminToken(), Map.of("items", List.of(Map.of("productoId", p,
                "codigoLote", "PD2", "fechaVencimiento", fecha(100), "cantidad", 15, "costoUnitario", 1200))), 200);
        assertThat(total.get("estado").asText()).isEqualTo("RECIBIDO");
        assertThat(total.get("cerradoEn").asText()).isNotBlank();
        post(ADM + "/pedidos/" + id + "/recepcion", adminToken(), Map.of("items", List.of(Map.of("productoId", p,
                "codigoLote", "PD3", "fechaVencimiento", fecha(100), "cantidad", 1, "costoUnitario", 1200))), 409);
        assertThat(get(ADM + "/pedidos?estado=RECIBIDO", adminToken(), 200).get("total").asLong()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void pedidosValidanDatosYPermitenCancelarOCerrarConPendientes() throws Exception {
        String prov = proveedor(3);
        String a = producto(1000, 0, 0, null);
        String b = producto(1000, 0, 0, null);
        post(ADM + "/pedidos", adminToken(), Map.of("proveedorId", prov, "items", List.of()), 400);
        assertThat(post(ADM + "/pedidos", adminToken(), Map.of("proveedorId", prov, "items", List.of(
                Map.of("productoId", a, "cantidad", 1), Map.of("productoId", a, "cantidad", 2))), 400).get("code").asText()).isEqualTo("PRODUCTO_REPETIDO");
        post(ADM + "/pedidos", adminToken(), Map.of("proveedorId", UUID.randomUUID().toString(), "items", List.of(Map.of("productoId", a, "cantidad", 1))), 400);

        String id = post(ADM + "/pedidos", adminToken(), Map.of("proveedorId", prov, "items", List.of(
                Map.of("productoId", a, "cantidad", 10), Map.of("productoId", b, "cantidad", 10))), 201).get("id").asText();
        post(ADM + "/pedidos/" + id + "/cerrar", adminToken(), Map.of("motivo", "Aún no se puede cerrar"), 409);
        post(ADM + "/pedidos/" + id + "/recepcion", adminToken(), Map.of("items", List.of()), 400);
        post(ADM + "/pedidos/" + id + "/recepcion", adminToken(), Map.of("items", List.of(Map.of("productoId", a, "codigoLote", "Q1",
                "fechaVencimiento", fecha(50), "cantidad", 1, "costoUnitario", 500))), 409);                    // aún en borrador
        post(ADM + "/pedidos/" + id + "/enviar", adminToken(), null, 200);
        post(ADM + "/pedidos/" + id + "/recepcion", adminToken(), Map.of("items", List.of(Map.of("productoId", a, "codigoLote", "Q1",
                "fechaVencimiento", fecha(50), "cantidad", 10, "costoUnitario", 500))), 200);
        post(ADM + "/pedidos/" + id + "/cancelar", adminToken(), Map.of("motivo", "Ya hay recepciones"), 409);
        JsonNode cerrado = post(ADM + "/pedidos/" + id + "/cerrar", adminToken(), Map.of("motivo", "El proveedor no entregará el resto"), 200);
        assertThat(cerrado.get("estado").asText()).isEqualTo("RECIBIDO");

        String id2 = post(ADM + "/pedidos", adminToken(), Map.of("proveedorId", prov, "items", List.of(Map.of("productoId", b, "cantidad", 3))), 201)
                .get("id").asText();
        post(ADM + "/pedidos/" + id2 + "/cancelar", adminToken(), Map.of("motivo", "Se compró en otro lado"), 200);
        post(ADM + "/pedidos/" + id2 + "/cancelar", adminToken(), Map.of("motivo", "Cancelar otra vez"), 409);

        String tk = tokenCajeraHabilitada(crearCajera());
        get(ADM + "/pedidos", tk, 403);
        get(ADM + "/reabastecimiento/sugerencias", tk, 403);
        post(ADM + "/pedidos", tk, Map.of("proveedorId", prov, "items", List.of(Map.of("productoId", a, "cantidad", 1))), 403);
    }
}
