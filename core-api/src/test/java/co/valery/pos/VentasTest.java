package co.valery.pos;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class VentasTest extends IntegrationTestBase {
    private static final String ADM = "/api/v1/admin";

    // ---------- helpers ----------

    private String fecha(int dias) {
        return LocalDate.now(ZoneId.of("America/Bogota")).plusDays(dias).toString();
    }

    private String producto(String unidad, int precio) throws Exception {
        String codigo = "V" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        int cat = jdbc.sql("select id from categorias order by id limit 1").query(Integer.class).single();
        Map<String, Object> b = new HashMap<>();
        b.put("codigo", codigo);
        b.put("nombre", "Producto " + codigo);
        b.put("categoriaId", cat);
        b.put("unidad", unidad);
        b.put("precioVenta", precio);
        b.put("costoReferencia", precio / 2);
        return post(ADM + "/productos", adminToken(), b, 201).get("id").asText();
    }

    private String lote(String productoId, String codigo, int dias, int cantidad) throws Exception {
        Map<String, Object> b = new HashMap<>();
        b.put("productoId", productoId);
        b.put("codigoLote", codigo);
        b.put("fechaVencimiento", fecha(dias));
        b.put("cantidad", cantidad);
        b.put("costoUnitario", 1000);
        return post(ADM + "/inventario/entradas", adminToken(), b, 201).get("id").asText();
    }

    private Map<String, Object> item(String productoId, Object cantidad) {
        return Map.of("productoId", productoId, "cantidad", cantidad);
    }

    private Map<String, Object> cuerpo(UUID key, List<Map<String, Object>> items, String forma, Object recibido) {
        Map<String, Object> b = new HashMap<>();
        b.put("idempotencyKey", key.toString());
        b.put("items", items);
        b.put("formaPago", forma);
        if (recibido != null) b.put("montoRecibido", recibido);
        return b;
    }

    private JsonNode vender(String tk, List<Map<String, Object>> items, String forma, Object recibido, int status) throws Exception {
        return post("/api/v1/ventas", tk, cuerpo(UUID.randomUUID(), items, forma, recibido), status);
    }

    private String cajeraConCaja() throws Exception {
        String tk = tokenCajeraHabilitada(crearCajera());
        post("/api/v1/caja/abrir", tk, Map.of("fondoInicial", 50000), 201);
        return tk;
    }

    private BigDecimal stock(String productoId) throws Exception {
        return get(ADM + "/productos/" + productoId, adminToken(), 200).get("stockTotal").decimalValue();
    }

    private BigDecimal stockLote(String loteId) {
        return jdbc.sql("select cantidad_disponible from lotes where id = cast(:l as uuid)").param("l", loteId).query(BigDecimal.class).single();
    }

    // ---------- pruebas ----------

    @Test
    void ventaDescuentaPorFefoAtravesDeLotesYGeneraKardex() throws Exception {
        String p = producto("UNIDAD", 3000);
        String l90 = lote(p, "L90", 90, 10);
        String l30 = lote(p, "L30", 30, 3);
        String l10 = lote(p, "L10", 10, 2);           // ingresó último pero vence primero
        String tk = cajeraConCaja();

        JsonNode v = vender(tk, List.of(item(p, 4)), "EFECTIVO", 20000, 201);
        assertThat(v.get("total").decimalValue()).isEqualByComparingTo("12000");
        assertThat(v.get("cambio").decimalValue()).isEqualByComparingTo("8000");
        assertThat(v.get("comprobante").asText()).startsWith("V-");
        JsonNode items = v.get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("codigoLote").asText()).isEqualTo("L10");
        assertThat(items.get(0).get("cantidad").decimalValue()).isEqualByComparingTo("2");
        assertThat(items.get(1).get("codigoLote").asText()).isEqualTo("L30");
        assertThat(items.get(1).get("cantidad").decimalValue()).isEqualByComparingTo("2");
        assertThat(items.get(0).has("costoUnitario")).isFalse();        // la cajera no ve costos

        assertThat(stockLote(l10)).isEqualByComparingTo("0");
        assertThat(stockLote(l30)).isEqualByComparingTo("1");
        assertThat(stockLote(l90)).isEqualByComparingTo("10");
        JsonNode kardex = get(ADM + "/inventario/movimientos?productoId=" + p + "&tipo=VENTA", adminToken(), 200);
        assertThat(kardex.get("total").asLong()).isEqualTo(2);
        BigDecimal suma = jdbc.sql("select coalesce(sum(cantidad),0) from movimientos_inventario where producto_id = cast(:p as uuid)")
                .param("p", p).query(BigDecimal.class).single();
        assertThat(suma).isEqualByComparingTo(stock(p));

        JsonNode admin = get("/api/v1/ventas/" + v.get("id").asText(), adminToken(), 200);
        assertThat(admin.get("items").get(0).get("costoUnitario").decimalValue()).isEqualByComparingTo("1000");
    }

    @Test
    void ventaEsAtomicaSiUnProductoNoTieneStockNadaSeDescuenta() throws Exception {
        String a = producto("UNIDAD", 1000);
        String b = producto("UNIDAD", 1000);
        lote(a, "A1", 60, 10);
        lote(b, "B1", 60, 1);
        String tk = cajeraConCaja();
        JsonNode e = vender(tk, List.of(item(a, 5), item(b, 2)), "TARJETA", null, 422);
        assertThat(e.get("code").asText()).isEqualTo("STOCK_INSUFICIENTE");
        assertThat(stock(a)).isEqualByComparingTo("10");
        assertThat(stock(b)).isEqualByComparingTo("1");
        assertThat(jdbc.sql("select count(*) from movimientos_inventario where producto_id = cast(:p as uuid) and tipo = 'VENTA'")
                .param("p", a).query(Long.class).single()).isZero();
    }

    @Test
    void lotesVencidosNoSeVendenAunqueTenganExistencias() throws Exception {
        String p = producto("UNIDAD", 1000);
        lote(p, "OK1", 60, 2);
        jdbc.sql("""
                insert into lotes(producto_id, codigo_lote, fecha_vencimiento, cantidad_inicial, cantidad_disponible, costo_unitario)
                values (cast(:p as uuid), 'VENC', current_date - 3, 50, 50, 500)
                """).param("p", p).update();
        String tk = cajeraConCaja();
        assertThat(vender(tk, List.of(item(p, 3)), "TARJETA", null, 422).get("code").asText()).isEqualTo("STOCK_INSUFICIENTE");
        vender(tk, List.of(item(p, 2)), "TARJETA", null, 201);
    }

    @Test
    void laMismaClaveNuncaDuplicaLaVenta() throws Exception {
        String p = producto("UNIDAD", 1000);
        lote(p, "I1", 60, 10);
        String tk = cajeraConCaja();
        UUID key = UUID.randomUUID();
        JsonNode primera = post("/api/v1/ventas", tk, cuerpo(key, List.of(item(p, 2)), "TARJETA", null), 201);
        JsonNode segunda = post("/api/v1/ventas", tk, cuerpo(key, List.of(item(p, 2)), "TARJETA", null), 200);
        assertThat(segunda.get("id").asText()).isEqualTo(primera.get("id").asText());
        assertThat(segunda.get("duplicada").asBoolean()).isTrue();
        assertThat(stock(p)).isEqualByComparingTo("8");

        String otra = cajeraConCaja();
        assertThat(post("/api/v1/ventas", otra, cuerpo(key, List.of(item(p, 2)), "TARJETA", null), 409).get("code").asText())
                .isEqualTo("IDEMPOTENCY_KEY_EN_USO");
    }

    @Test
    void soloLaAdministradoraAplicaDescuentos() throws Exception {
        String p = producto("UNIDAD", 1000);
        lote(p, "D1", 60, 20);
        String tk = cajeraConCaja();
        Map<String, Object> conDescuento = cuerpo(UUID.randomUUID(), List.of(item(p, 2)), "TARJETA", null);
        conDescuento.put("descuento", 500);
        assertThat(post("/api/v1/ventas", tk, conDescuento, 403).get("code").asText()).isEqualTo("DESCUENTO_NO_PERMITIDO");
        assertThat(get(ADM + "/auditoria?accion=DESCUENTO_DENEGADO", adminToken(), 200).get("total").asLong()).isGreaterThanOrEqualTo(1);

        conDescuento.put("idempotencyKey", UUID.randomUUID().toString());
        JsonNode v = post("/api/v1/ventas", adminToken(), conDescuento, 201);
        assertThat(v.get("total").decimalValue()).isEqualByComparingTo("1500");
        conDescuento.put("idempotencyKey", UUID.randomUUID().toString());
        conDescuento.put("descuento", 99999);
        assertThat(post("/api/v1/ventas", adminToken(), conDescuento, 422).get("code").asText()).isEqualTo("DESCUENTO_INVALIDO");
    }

    @Test
    void laCajeraDebeAbrirCajaPeroLaAdministradoraNo() throws Exception {
        String p = producto("UNIDAD", 1000);
        lote(p, "C1", 60, 20);
        String tk = tokenCajeraHabilitada(crearCajera());
        assertThat(vender(tk, List.of(item(p, 1)), "TARJETA", null, 409).get("code").asText()).isEqualTo("CAJA_CERRADA");
        post("/api/v1/caja/abrir", tk, Map.of("fondoInicial", 10000), 201);
        assertThat(post("/api/v1/caja/abrir", tk, Map.of("fondoInicial", 10000), 409).get("code").asText()).isEqualTo("CAJA_YA_ABIERTA");
        vender(tk, List.of(item(p, 1)), "TARJETA", null, 201);
        vender(adminToken(), List.of(item(p, 1)), "TARJETA", null, 201);
    }

    @Test
    void validaPagosYCantidades() throws Exception {
        String p = producto("UNIDAD", 1000);
        String g = producto("GRAMO", 10);
        lote(p, "P1", 60, 20);
        lote(g, "G1", 60, 1000);
        String tk = cajeraConCaja();
        assertThat(vender(tk, List.of(item(p, 1)), "EFECTIVO", null, 400).get("code").asText()).isEqualTo("MONTO_RECIBIDO_REQUERIDO");
        assertThat(vender(tk, List.of(item(p, 2)), "EFECTIVO", 1500, 422).get("code").asText()).isEqualTo("MONTO_INSUFICIENTE");
        assertThat(vender(tk, List.of(item(p, 1)), "CHEQUE", null, 400).get("code").asText()).isEqualTo("FORMA_PAGO_INVALIDA");
        assertThat(vender(tk, List.of(item(p, 1.5)), "TARJETA", null, 422).get("code").asText()).isEqualTo("CANTIDAD_INVALIDA");
        vender(tk, List.of(item(g, 2.5)), "TARJETA", null, 201);           // a granel sí admite decimales
        vender(tk, List.of(), "TARJETA", null, 400);
        vender(tk, List.of(item(p, 0)), "TARJETA", null, 400);
        vender(tk, List.of(item(UUID.randomUUID().toString(), 1)), "TARJETA", null, 404);
        post("/api/v1/productos/" + p, tk, null, 405);

        post(ADM + "/productos/" + p + "/baja", adminToken(), null, 200);
        assertThat(vender(tk, List.of(item(p, 1)), "TARJETA", null, 422).get("code").asText()).isEqualTo("PRODUCTO_INACTIVO");
    }

    @Test
    void cadaCajeraSoloVeSusVentasYElComprobanteSeGenera() throws Exception {
        String p = producto("UNIDAD", 2500);
        lote(p, "S1", 60, 20);
        String tk1 = cajeraConCaja();
        String tk2 = cajeraConCaja();
        JsonNode v = vender(tk1, List.of(item(p, 2)), "EFECTIVO", 10000, 201);
        String id = v.get("id").asText();
        get("/api/v1/ventas/" + id, tk1, 200);
        get("/api/v1/ventas/" + id, tk2, 404);
        get("/api/v1/ventas/" + id + "/comprobante", tk2, 404);
        assertThat(get("/api/v1/ventas", tk2, 200).get("items")).isEmpty();
        assertThat(get("/api/v1/ventas", tk1, 200).get("items")).hasSize(1);
        assertThat(get("/api/v1/ventas", adminToken(), 200).get("total").asLong()).isGreaterThanOrEqualTo(1);

        JsonNode c = get("/api/v1/ventas/" + id + "/comprobante", tk1, 200);
        assertThat(c.get("negocio").asText()).isEqualTo("Droguería & Colmena Valery");
        assertThat(c.get("total").decimalValue()).isEqualByComparingTo("5000");
        assertThat(c.get("cambio").decimalValue()).isEqualByComparingTo("5000");
        assertThat(c.get("items")).hasSize(1);
        assertThat(c.get("leyenda").asText()).contains("No constituye factura electrónica");

        // Las funciones administrativas siguen vedadas
        post(ADM + "/ventas/" + id + "/anular", tk1, Map.of("motivo", "Quiero anularla"), 403);
        get(ADM + "/ventas/" + id + "/verificar-sello", tk1, 403);
    }

    @Test
    void anularReponeLotesDejaKardexYConservaElRegistroSellado() throws Exception {
        String p = producto("UNIDAD", 1000);
        String l1 = lote(p, "N1", 20, 2);
        String l2 = lote(p, "N2", 90, 10);
        String tk = cajeraConCaja();
        JsonNode v = vender(tk, List.of(item(p, 5)), "TARJETA", null, 201);
        String id = v.get("id").asText();
        assertThat(stock(p)).isEqualByComparingTo("7");

        post(ADM + "/ventas/" + id + "/anular", adminToken(), Map.of("motivo", "no"), 400);
        JsonNode a = post(ADM + "/ventas/" + id + "/anular", adminToken(), Map.of("motivo", "Error de digitación del producto"), 200);
        assertThat(a.get("estado").asText()).isEqualTo("ANULADA");
        assertThat(stock(p)).isEqualByComparingTo("12");
        assertThat(stockLote(l1)).isEqualByComparingTo("2");
        assertThat(stockLote(l2)).isEqualByComparingTo("10");
        assertThat(post(ADM + "/ventas/" + id + "/anular", adminToken(), Map.of("motivo", "Intento repetido"), 409).get("code").asText())
                .isEqualTo("VENTA_YA_ANULADA");

        JsonNode kardex = get(ADM + "/inventario/movimientos?productoId=" + p + "&tipo=ANULACION_VENTA", adminToken(), 200);
        assertThat(kardex.get("total").asLong()).isEqualTo(2);
        assertThat(get(ADM + "/ventas/" + id + "/verificar-sello", adminToken(), 200).get("integro").asBoolean()).isTrue();
        assertThat(get("/api/v1/ventas/" + id, adminToken(), 200).get("motivoAnulacion").asText()).contains("Error de digitación");
        // La venta anulada no es modificable ni siquiera directamente en la base
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> jdbc.sql("update ventas set estado = 'CONFIRMADA' where id = cast(:i as uuid)").param("i", id).update());
    }

    @Test
    void elSelloDeIntegridadSeVerificaYUsaLaHoraDelServidor() throws Exception {
        String p = producto("UNIDAD", 1000);
        lote(p, "H1", 60, 5);
        String tk = cajeraConCaja();
        Map<String, Object> b = cuerpo(UUID.randomUUID(), List.of(item(p, 1)), "TARJETA", null);
        b.put("fechaCliente", OffsetDateTime.now().minusYears(3).toString());      // un cliente manipulado no puede fijar la fecha
        JsonNode v = post("/api/v1/ventas", tk, b, 201);
        OffsetDateTime servidor = OffsetDateTime.parse(v.get("fechaServidor").asText());
        assertThat(servidor).isAfter(OffsetDateTime.now().minusMinutes(5));
        assertThat(get(ADM + "/ventas/" + v.get("id").asText() + "/verificar-sello", adminToken(), 200).get("integro").asBoolean()).isTrue();
        assertThat(v.get("hashSellado").asText()).hasSize(64);
    }

    @Test
    void cierreDeCajaCuadraEfectivoYExigeExplicarLasDiferencias() throws Exception {
        String p = producto("UNIDAD", 3000);
        lote(p, "K1", 60, 50);
        String tk = cajeraConCaja();                                   // fondo 50.000
        vender(tk, List.of(item(p, 4)), "EFECTIVO", 20000, 201);       // +12.000 en efectivo
        vender(tk, List.of(item(p, 2)), "TARJETA", null, 201);         // 6.000 por tarjeta (no entra a la caja)
        JsonNode anulable = vender(tk, List.of(item(p, 1)), "EFECTIVO", 3000, 201);
        post(ADM + "/ventas/" + anulable.get("id").asText() + "/anular", adminToken(), Map.of("motivo", "Cliente se arrepintió"), 200);

        JsonNode actual = get("/api/v1/caja/actual", tk, 200);
        assertThat(actual.get("resumen").get("ventas").asLong()).isEqualTo(2);
        assertThat(actual.get("resumen").get("anuladas").asLong()).isEqualTo(1);
        assertThat(actual.get("resumen").get("efectivoEsperado").decimalValue()).isEqualByComparingTo("62000");

        JsonNode sinExplicar = post("/api/v1/caja/cerrar", tk, Map.of("efectivoContado", 60000), 400);
        assertThat(sinExplicar.get("code").asText()).isEqualTo("OBSERVACION_REQUERIDA");
        JsonNode cierre = post("/api/v1/caja/cerrar", tk, Map.of("efectivoContado", 60000, "observaciones", "Faltaron 2.000 de vueltas mal dadas"), 200);
        assertThat(cierre.get("estado").asText()).isEqualTo("CERRADA");
        assertThat(cierre.get("diferencia").decimalValue()).isEqualByComparingTo("-2000");
        assertThat(cierre.get("resumen").get("porFormaPago").get("TARJETA").decimalValue()).isEqualByComparingTo("6000");

        assertThat(vender(tk, List.of(item(p, 1)), "TARJETA", null, 409).get("code").asText()).isEqualTo("CAJA_CERRADA");
        get("/api/v1/caja/actual", tk, 404);
        assertThat(get(ADM + "/caja/sesiones?estado=CERRADA", adminToken(), 200).get("total").asLong()).isGreaterThanOrEqualTo(1);
        get(ADM + "/caja/sesiones", tk, 403);
    }

    @Test
    void laAdministradoraPuedeCerrarLaCajaDeUnaCajeraAusenteYLuegoDarlaDeBaja() throws Exception {
        Cajera c = crearCajera();
        String tk = tokenCajeraHabilitada(c);
        JsonNode s = post("/api/v1/caja/abrir", tk, Map.of("fondoInicial", 20000), 201);
        post(ADM + "/usuarios/" + c.id() + "/desactivar", adminToken(), Map.of("motivo", "Fin de contrato"), 409);
        JsonNode cerrada = post(ADM + "/caja/sesiones/" + s.get("id").asText() + "/cerrar-forzado", adminToken(),
                Map.of("motivo", "La cajera no regresó al turno"), 200);
        assertThat(cerrada.get("estado").asText()).isEqualTo("CERRADA");
        assertThat(cerrada.get("observaciones").asText()).contains("CIERRE FORZADO");
        post(ADM + "/caja/sesiones/" + s.get("id").asText() + "/cerrar-forzado", adminToken(), Map.of("motivo", "Otra vez cerrar"), 409);
        post(ADM + "/usuarios/" + c.id() + "/desactivar", adminToken(), Map.of("motivo", "Fin de contrato"), 200);
    }

    @Test
    void sincronizacionOfflineEsIdempotenteRechazaSinStockYConservaLaVenta() throws Exception {
        String p = producto("UNIDAD", 1000);
        String lote = lote(p, "O1", 60, 5);
        String tk = tokenCajeraHabilitada(crearCajera());               // la sincronización no exige caja abierta

        UUID kA = UUID.randomUUID(), kB = UUID.randomUUID(), kC = UUID.randomUUID();
        Map<String, Object> a = cuerpo(kA, List.of(item(p, 3)), "TARJETA", null);
        a.put("fechaCliente", OffsetDateTime.now().minusHours(3).toString());
        Map<String, Object> b = cuerpo(kB, List.of(item(p, 3)), "TARJETA", null);      // ya no alcanzará el stock
        b.put("fechaCliente", OffsetDateTime.now().minusHours(2).toString());
        Map<String, Object> c = cuerpo(kC, List.of(item(p, 1)), "TARJETA", null);
        c.put("fechaCliente", OffsetDateTime.now().minusHours(1).toString());
        Map<String, Object> lote1 = Map.of("ventas", List.of(c, b, a));              // llegan desordenadas

        JsonNode r = post("/api/v1/ventas/sincronizar", tk, lote1, 200);
        assertThat(r.get("confirmadas").asInt()).isEqualTo(2);
        assertThat(r.get("rechazadas").asInt()).isEqualTo(1);
        Map<String, JsonNode> porClave = new HashMap<>();
        for (JsonNode x : r.get("resultados")) porClave.put(x.get("idempotencyKey").asText(), x);
        assertThat(porClave.get(kA.toString()).get("estado").asText()).isEqualTo("CONFIRMADA");
        assertThat(porClave.get(kB.toString()).get("estado").asText()).isEqualTo("RECHAZADA");
        assertThat(porClave.get(kB.toString()).get("codigo").asText()).isEqualTo("STOCK_INSUFICIENTE");
        assertThat(porClave.get(kC.toString()).get("estado").asText()).isEqualTo("CONFIRMADA");
        assertThat(stockLote(lote)).isEqualByComparingTo("1");

        JsonNode venta = get("/api/v1/ventas/" + porClave.get(kA.toString()).get("ventaId").asText(), tk, 200);
        assertThat(venta.get("origen").asText()).isEqualTo("OFFLINE_SYNC");
        assertThat(OffsetDateTime.parse(venta.get("fechaServidor").asText())).isAfter(OffsetDateTime.now().minusMinutes(5));

        // La cajera es notificada y la venta rechazada se conserva completa
        JsonNode rechazos = get("/api/v1/ventas/sincronizacion/rechazos", tk, 200);
        assertThat(rechazos.get("total").asLong()).isEqualTo(1);
        assertThat(rechazos.get("items").get(0).get("payload").get("idempotencyKey").asText()).isEqualTo(kB.toString());
        assertThat(get(ADM + "/sync/rechazos", adminToken(), 200).get("total").asLong()).isGreaterThanOrEqualTo(1);

        // Reponen stock y la app reintenta todo el lote: nada se duplica y la pendiente ahora entra
        post(ADM + "/inventario/entradas", adminToken(), Map.of("productoId", p, "codigoLote", "O2",
                "fechaVencimiento", fecha(90), "cantidad", 10, "costoUnitario", 1000), 201);
        JsonNode r2 = post("/api/v1/ventas/sincronizar", tk, lote1, 200);
        assertThat(r2.get("duplicadas").asInt()).isEqualTo(2);
        assertThat(r2.get("confirmadas").asInt()).isEqualTo(1);
        assertThat(r2.get("rechazadas").asInt()).isZero();
        assertThat(get("/api/v1/ventas/sincronizacion/rechazos", tk, 200).get("total").asLong()).isZero();
        assertThat(get("/api/v1/ventas?size=50", tk, 200).get("total").asLong()).isEqualTo(3);
        assertThat(stock(p)).isEqualByComparingTo("8");               // 5 + 10 - 3 - 1 - 3
    }

    @Test
    void administradoraPuedeResolverManualmenteUnRechazoDeSincronizacion() throws Exception {
        String p = producto("UNIDAD", 1000);
        lote(p, "X1", 60, 1);
        String tk = tokenCajeraHabilitada(crearCajera());
        UUID k = UUID.randomUUID();
        JsonNode r = post("/api/v1/ventas/sincronizar", tk, Map.of("ventas", List.of(cuerpo(k, List.of(item(p, 9)), "TARJETA", null))), 200);
        assertThat(r.get("rechazadas").asInt()).isEqualTo(1);
        String rechazoId = get(ADM + "/sync/rechazos", adminToken(), 200).get("items").get(0).get("id").asText();
        post(ADM + "/sync/rechazos/" + rechazoId + "/resolver", tk, Map.of("nota", "Resuelto en persona"), 403);
        post(ADM + "/sync/rechazos/" + rechazoId + "/resolver", adminToken(), Map.of("nota", "Se atendió al cliente en mostrador"), 204);
        post(ADM + "/sync/rechazos/" + rechazoId + "/resolver", adminToken(), Map.of("nota", "Se atendió al cliente en mostrador"), 409);
    }

    @Test
    void ventasSimultaneasDelUltimoArticuloSoloUnaTieneExito() throws Exception {
        String p = producto("UNIDAD", 1000);
        String lote = lote(p, "Z1", 60, 1);
        String tk = adminToken();
        int hilos = 6;
        ExecutorService ex = Executors.newFixedThreadPool(hilos);
        CountDownLatch listos = new CountDownLatch(hilos);
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<Integer>> futuros = new ArrayList<>();
        for (int i = 0; i < hilos; i++) {
            String json = om.writeValueAsString(cuerpo(UUID.randomUUID(), List.of(item(p, 1)), "TARJETA", null));
            futuros.add(ex.submit(() -> {
                listos.countDown();
                salida.await();
                return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/ventas").header("Authorization", "Bearer " + tk)
                        .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn().getResponse().getStatus();
            }));
        }
        listos.await();
        salida.countDown();
        int creadas = 0, rechazadas = 0;
        for (Future<Integer> f : futuros) {
            int s = f.get();
            if (s == 201) creadas++;
            else if (s == 422) rechazadas++;
        }
        ex.shutdown();
        assertThat(creadas).isEqualTo(1);
        assertThat(rechazadas).isEqualTo(hilos - 1);
        assertThat(stockLote(lote)).isEqualByComparingTo("0");
    }
}
