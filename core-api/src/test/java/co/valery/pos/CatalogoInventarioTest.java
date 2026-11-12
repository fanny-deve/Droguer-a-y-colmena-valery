package co.valery.pos;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CatalogoInventarioTest extends IntegrationTestBase {
    private static final String ADM = "/api/v1/admin";

    private int categoriaId() {
        return jdbc.sql("select id from categorias order by id limit 1").query(Integer.class).single();
    }

    private String fecha(int dias) {
        return LocalDate.now(ZoneId.of("America/Bogota")).plusDays(dias).toString();
    }

    private String codigoUnico() {
        return "P" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    private JsonNode crearProducto(String codigo, boolean vence, int min, int reorden) throws Exception {
        Map<String, Object> b = new HashMap<>();
        b.put("codigo", codigo);
        b.put("nombre", "Producto " + codigo);
        b.put("categoriaId", categoriaId());
        b.put("unidad", "UNIDAD");
        b.put("precioVenta", 3000);
        b.put("costoReferencia", 1500);
        b.put("manejaVencimiento", vence);
        b.put("stockMinimo", min);
        b.put("puntoReorden", reorden);
        return post(ADM + "/productos", adminToken(), b, 201);
    }

    private JsonNode entrada(String productoId, String lote, String vence, int cantidad, int status) throws Exception {
        Map<String, Object> b = new HashMap<>();
        b.put("productoId", productoId);
        b.put("codigoLote", lote);
        if (vence != null) b.put("fechaVencimiento", vence);
        b.put("cantidad", cantidad);
        b.put("costoUnitario", 1500);
        return post(ADM + "/inventario/entradas", adminToken(), b, status);
    }

    private JsonNode ajuste(String loteId, String tipo, String sentido, String cantidad, int status) throws Exception {
        return post(ADM + "/inventario/ajustes", adminToken(),
                Map.of("loteId", loteId, "tipo", tipo, "sentido", sentido, "cantidad", new BigDecimal(cantidad), "motivo", "Motivo de prueba"), status);
    }

    private BigDecimal stockVendible(String productoId) throws Exception {
        return get(ADM + "/productos/" + productoId, adminToken(), 200).get("stockVendible").decimalValue();
    }

    private Map<String, String> alertasAbiertas(String productoId) throws Exception {
        JsonNode r = get(ADM + "/alertas?productoId=" + productoId + "&size=100", adminToken(), 200);
        Map<String, String> m = new HashMap<>();
        for (JsonNode a : r.get("items")) {
            m.put(a.has("loteId") ? a.get("loteId").asText() : "producto", a.get("tipo").asText());
        }
        return m;
    }

    @Test
    void productoValidaEan13UnicidadYSeparaLoQueVeLaCajera() throws Exception {
        Map<String, Object> malEan = new HashMap<>(Map.of("codigo", "4006381333932", "nombre", "EAN inválido", "categoriaId", categoriaId(),
                "unidad", "UNIDAD", "precioVenta", 1000, "costoReferencia", 500));
        assertThat(post(ADM + "/productos", adminToken(), malEan, 400).get("code").asText()).isEqualTo("CODIGO_EAN_INVALIDO");
        malEan.put("codigo", "4006381333931");
        post(ADM + "/productos", adminToken(), malEan, 201);

        String codigo = codigoUnico();
        JsonNode p = crearProducto(codigo, true, 0, 0);
        assertThat(p.get("margenPorcentaje").decimalValue()).isEqualByComparingTo("50");
        assertThat(post(ADM + "/productos", adminToken(), Map.of("codigo", codigo.toLowerCase(), "nombre", "Duplicado", "categoriaId",
                categoriaId(), "unidad", "UNIDAD", "precioVenta", 1000, "costoReferencia", 500), 409).get("code").asText())
                .isEqualTo("CODIGO_DUPLICADO");

        String tk = tokenCajeraHabilitada(crearCajera());
        JsonNode busqueda = get("/api/v1/productos?q=" + codigo, tk, 200);
        assertThat(busqueda.get("items")).hasSize(1);
        JsonNode item = busqueda.get("items").get(0);
        assertThat(item.has("precioVenta")).isTrue();
        assertThat(item.has("costoReferencia")).isFalse();
        assertThat(item.has("margenPorcentaje")).isFalse();
        assertThat(item.has("proveedorPrincipalId")).isFalse();
        assertThat(item.has("clasificacionAbc")).isFalse();
        assertThat(get("/api/v1/productos/codigo/" + codigo, tk, 200).get("id").asText()).isEqualTo(p.get("id").asText());
        get(ADM + "/productos", tk, 403);
        post(ADM + "/productos", tk, Map.of("codigo", "XYZ123"), 403);

        post(ADM + "/productos/" + p.get("id").asText() + "/baja", adminToken(), null, 200);
        assertThat(get("/api/v1/productos?q=" + codigo, tk, 200).get("items")).isEmpty();
        get("/api/v1/productos/codigo/" + codigo, tk, 404);
        assertThat(entrada(p.get("id").asText(), "L1", fecha(100), 5, 422).get("code").asText()).isEqualTo("PRODUCTO_INACTIVO");
        post(ADM + "/productos/" + p.get("id").asText() + "/reactivar", adminToken(), null, 200);
    }

    @Test
    void entradaDeLoteExigeVencimientoVigenteYAlimentaKardexYStock() throws Exception {
        String id = crearProducto(codigoUnico(), true, 0, 0).get("id").asText();
        assertThat(entrada(id, "A1", fecha(-1), 10, 422).get("code").asText()).isEqualTo("LOTE_VENCIDO");
        assertThat(entrada(id, "A1", null, 10, 400).get("code").asText()).isEqualTo("FECHA_VENCIMIENTO_REQUERIDA");

        JsonNode lote = entrada(id, "A1", fecha(90), 10, 201);
        assertThat(lote.get("cantidadDisponible").decimalValue()).isEqualByComparingTo("10");
        assertThat(lote.get("vigencia").asText()).isEqualTo("VIGENTE");
        assertThat(entrada(id, "a1", fecha(90), 5, 409).get("code").asText()).isEqualTo("LOTE_DUPLICADO");
        entrada(id, "A2", fecha(120), 7, 201);

        assertThat(stockVendible(id)).isEqualByComparingTo("17");
        JsonNode kardex = get(ADM + "/inventario/movimientos?productoId=" + id, adminToken(), 200);
        assertThat(kardex.get("total").asLong()).isEqualTo(2);
        assertThat(kardex.get("items").get(0).get("tipo").asText()).isEqualTo("ENTRADA");
        BigDecimal suma = jdbc.sql("select coalesce(sum(cantidad),0) from movimientos_inventario where producto_id = cast(:p as uuid)")
                .param("p", id).query(BigDecimal.class).single();
        assertThat(suma).isEqualByComparingTo("17");

        // El producto sin vencimiento (colmena/higiene) no exige fecha
        String sinVenc = crearProducto(codigoUnico(), false, 0, 0).get("id").asText();
        assertThat(entrada(sinVenc, "S1", null, 4, 201).get("vigencia").asText()).isEqualTo("SIN_VENCIMIENTO");
        get(ADM + "/inventario/lotes", tokenCajeraHabilitada(crearCajera()), 403);
    }

    @Test
    void mermasYAjustesExigenMotivoValidanStockYQuedanValorados() throws Exception {
        String id = crearProducto(codigoUnico(), true, 0, 0).get("id").asText();
        String lote = entrada(id, "M1", fecha(60), 10, 201).get("id").asText();

        assertThat(ajuste(lote, "DANO", "NEGATIVO", "11", 422).get("code").asText()).isEqualTo("STOCK_INSUFICIENTE");
        assertThat(ajuste(lote, "VENCIMIENTO", "POSITIVO", "1", 400).get("code").asText()).isEqualTo("AJUSTE_INVALIDO");
        assertThat(ajuste(lote, "INVENTADO", "NEGATIVO", "1", 400).get("code").asText()).isEqualTo("TIPO_AJUSTE_INVALIDO");
        post(ADM + "/inventario/ajustes", adminToken(), Map.of("loteId", lote, "tipo", "DANO", "sentido", "NEGATIVO",
                "cantidad", 1, "motivo", "ok"), 400);

        JsonNode merma = ajuste(lote, "DANO", "NEGATIVO", "3", 201);
        assertThat(merma.get("valorTotal").decimalValue()).isEqualByComparingTo("4500");
        assertThat(stockVendible(id)).isEqualByComparingTo("7");
        JsonNode pos = ajuste(lote, "DIFERENCIA_CONTEO", "POSITIVO", "2", 201);
        assertThat(pos.get("tipo").asText()).isEqualTo("DIFERENCIA_CONTEO");
        assertThat(stockVendible(id)).isEqualByComparingTo("9");

        JsonNode kardex = get(ADM + "/inventario/movimientos?productoId=" + id + "&size=10", adminToken(), 200);
        assertThat(kardex.get("total").asLong()).isEqualTo(3);
        BigDecimal suma = jdbc.sql("select coalesce(sum(cantidad),0) from movimientos_inventario where producto_id = cast(:p as uuid)")
                .param("p", id).query(BigDecimal.class).single();
        assertThat(suma).isEqualByComparingTo(stockVendible(id));       // el kardex siempre cuadra con las existencias
        assertThat(get(ADM + "/inventario/ajustes?productoId=" + id, adminToken(), 200).get("total").asLong()).isEqualTo(2);
        assertThat(get(ADM + "/auditoria?accion=CREAR_AJUSTE&size=5", adminToken(), 200).get("total").asLong()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void alertasDeVencimientoSeGeneranPorUmbralYSeResuelvenSolas() throws Exception {
        String id = crearProducto(codigoUnico(), true, 0, 0).get("id").asText();
        String l3 = entrada(id, "V3", fecha(3), 5, 201).get("id").asText();
        String l12 = entrada(id, "V12", fecha(12), 5, 201).get("id").asText();
        String l25 = entrada(id, "V25", fecha(25), 5, 201).get("id").asText();
        String l60 = entrada(id, "V60", fecha(60), 5, 201).get("id").asText();
        String vencido = jdbc.sql("""
                insert into lotes(producto_id, codigo_lote, fecha_vencimiento, cantidad_inicial, cantidad_disponible, costo_unitario)
                values (cast(:p as uuid), 'VENCIDO1', current_date - 5, 4, 4, 1500) returning id::text
                """).param("p", id).query(String.class).single();

        post(ADM + "/alertas/evaluar", adminToken(), null, 200);
        Map<String, String> a = alertasAbiertas(id);
        assertThat(a.get(l3)).isEqualTo("VENCIMIENTO_7");
        assertThat(a.get(l12)).isEqualTo("VENCIMIENTO_15");
        assertThat(a.get(l25)).isEqualTo("VENCIMIENTO_30");
        assertThat(a).doesNotContainKey(l60);
        assertThat(a.get(vencido)).isEqualTo("VENCIDO");

        // Lo vencido no es vendible: 5+5+5+5 vigentes, los 4 vencidos quedan fuera
        assertThat(stockVendible(id)).isEqualByComparingTo("20");
        JsonNode prod = get(ADM + "/productos/" + id, adminToken(), 200);
        assertThat(prod.get("stockVencido").decimalValue()).isEqualByComparingTo("4");

        // Re-evaluar no duplica
        post(ADM + "/alertas/evaluar", adminToken(), null, 200);
        assertThat(alertasAbiertas(id)).hasSize(4);

        // Al agotar el lote, su alerta se resuelve sola
        ajuste(l3, "DANO", "NEGATIVO", "5", 201);
        assertThat(alertasAbiertas(id)).doesNotContainKey(l3);

        // Marcar vista cambia el estado pero sigue abierta
        JsonNode lista = get(ADM + "/alertas?productoId=" + id, adminToken(), 200);
        String alertaId = lista.get("items").get(0).get("id").asText();
        assertThat(post(ADM + "/alertas/" + alertaId + "/vista", adminToken(), null, 200).get("estado").asText()).isEqualTo("VISTA");
        get(ADM + "/alertas", tokenCajeraHabilitada(crearCajera()), 403);
    }

    @Test
    void alertasDeReabastecimientoSiguenElPuntoDeReordenYElStockMinimo() throws Exception {
        String id = crearProducto(codigoUnico(), true, 10, 20).get("id").asText();
        String lote = entrada(id, "R1", fecha(200), 15, 201).get("id").asText();
        assertThat(alertasAbiertas(id).get("producto")).isEqualTo("PUNTO_REORDEN");

        ajuste(lote, "DANO", "NEGATIVO", "10", 201);          // quedan 5: por debajo del mínimo
        assertThat(alertasAbiertas(id)).containsEntry("producto", "STOCK_MINIMO").hasSize(1);

        entrada(id, "R2", fecha(200), 100, 201);               // repone
        assertThat(alertasAbiertas(id)).isEmpty();
    }

    @Test
    void bloquearUnLoteLoSacaDeLaVentaYSeAuditaConMotivo() throws Exception {
        String id = crearProducto(codigoUnico(), true, 0, 0).get("id").asText();
        String lote = entrada(id, "B1", fecha(80), 10, 201).get("id").asText();
        entrada(id, "B2", fecha(90), 6, 201);

        post(ADM + "/inventario/lotes/" + lote + "/bloquear", adminToken(), Map.of("motivo", "no"), 400);
        assertThat(post(ADM + "/inventario/lotes/" + lote + "/bloquear", adminToken(), Map.of("motivo", "Retiro por alerta sanitaria"), 200)
                .get("estado").asText()).isEqualTo("BLOQUEADO");
        assertThat(stockVendible(id)).isEqualByComparingTo("6");
        post(ADM + "/inventario/lotes/" + lote + "/bloquear", adminToken(), Map.of("motivo", "Retiro por alerta sanitaria"), 409);
        post(ADM + "/inventario/lotes/" + lote + "/desbloquear", adminToken(), Map.of("motivo", "Resuelto con el proveedor"), 200);
        assertThat(stockVendible(id)).isEqualByComparingTo("16");
        assertThat(get(ADM + "/auditoria?accion=BLOQUEAR_LOTE", adminToken(), 200).get("total").asLong()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void proveedoresSoloLosGestionaLaAdministradora() throws Exception {
        String nombre = "Proveedor " + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> body = Map.of("nombre", nombre, "nit", "900123456-1", "tiempoEntregaDias", 5, "email", "VENTAS@Prov.com");
        JsonNode p = post(ADM + "/proveedores", adminToken(), body, 201);
        assertThat(p.get("email").asText()).isEqualTo("ventas@prov.com");
        assertThat(post(ADM + "/proveedores", adminToken(), body, 409).get("code").asText()).isEqualTo("PROVEEDOR_DUPLICADO");
        post(ADM + "/proveedores", adminToken(), Map.of("nombre", "X", "tiempoEntregaDias", 5), 400);
        post(ADM + "/proveedores", adminToken(), Map.of("nombre", "Otro proveedor", "tiempoEntregaDias", 500), 400);

        String prod = crearProducto(codigoUnico(), true, 0, 0).get("id").asText();
        JsonNode lista = put(ADM + "/proveedores/" + p.get("id").asText() + "/productos", adminToken(),
                java.util.List.of(Map.of("productoId", prod, "costoUltimo", 1200)), 200);
        assertThat(lista).hasSize(1);
        put(ADM + "/proveedores/" + p.get("id").asText() + "/productos", adminToken(),
                java.util.List.of(Map.of("productoId", UUID.randomUUID().toString())), 400);

        String tk = tokenCajeraHabilitada(crearCajera());
        get(ADM + "/proveedores", tk, 403);
        post(ADM + "/proveedores", tk, body, 403);
        assertThat(post(ADM + "/proveedores/" + p.get("id").asText() + "/desactivar", adminToken(), null, 200).get("activo").asBoolean()).isFalse();
    }

    @Test
    void categoriasLasVenTodosPeroSoloLaAdministradoraLasCrea() throws Exception {
        String tk = tokenCajeraHabilitada(crearCajera());
        assertThat(get("/api/v1/categorias", tk, 200)).isNotEmpty();
        String nombre = "Categoría " + UUID.randomUUID().toString().substring(0, 6);
        post(ADM + "/categorias", tk, Map.of("nombre", nombre), 403);
        post(ADM + "/categorias", adminToken(), Map.of("nombre", nombre), 201);
        assertThat(post(ADM + "/categorias", adminToken(), Map.of("nombre", nombre.toUpperCase()), 409).get("code").asText())
                .isEqualTo("CATEGORIA_DUPLICADA");
    }

    @Test
    void configuracionSoloAceptaClavesExistentesDelMismoTipo() throws Exception {
        assertThat(get(ADM + "/configuracion", adminToken(), 200).size()).isGreaterThanOrEqualTo(10);
        put(ADM + "/configuracion/no.existe", adminToken(), Map.of("valor", 1), 404);
        put(ADM + "/configuracion/seguridad.max_intentos", adminToken(), Map.of("valor", "cinco"), 400);
        put(ADM + "/configuracion/seguridad.max_intentos", adminToken(), Map.of("valor", -1), 400);
        put(ADM + "/configuracion/alertas.dias_vencimiento", adminToken(), Map.of("valor", java.util.List.of(30, 15)), 400);
        get(ADM + "/configuracion", tokenCajeraHabilitada(crearCajera()), 403);
    }
}
