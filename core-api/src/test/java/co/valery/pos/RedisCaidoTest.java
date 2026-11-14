package co.valery.pos;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Redis habilitado pero inalcanzable: el sistema debe seguir vendiendo y consultando sin ninguna degradación funcional. */
class RedisCaidoTest extends IntegrationTestBase {

    @DynamicPropertySource
    static void redisInalcanzable(DynamicPropertyRegistry r) {
        r.add("app.redis.enabled", () -> "true");
        r.add("spring.data.redis.url", () -> "redis://127.0.0.1:1");
    }

    @Test
    void sinRedisTodoSigueFuncionandoContraPostgreSql() throws Exception {
        String codigo = "RK" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        int cat = jdbc.sql("select id from categorias order by id limit 1").query(Integer.class).single();
        String p = post("/api/v1/admin/productos", adminToken(), Map.of("codigo", codigo, "nombre", "Producto " + codigo, "categoriaId", cat,
                "unidad", "UNIDAD", "precioVenta", 3000, "costoReferencia", 1500), 201).get("id").asText();
        post("/api/v1/admin/inventario/entradas", adminToken(), Map.of("productoId", p, "codigoLote", "K1",
                "fechaVencimiento", LocalDate.now().plusDays(100).toString(), "cantidad", 5, "costoUnitario", 1000), 201);
        String tk = tokenCajeraHabilitada(crearCajera());
        for (int i = 0; i < 3; i++) {
            assertThat(get("/api/v1/productos?q=" + codigo, tk, 200).get("items")).hasSize(1);
        }
        post("/api/v1/caja/abrir", tk, Map.of("fondoInicial", 1000), 201);
        post("/api/v1/ventas", tk, Map.of("idempotencyKey", UUID.randomUUID().toString(),
                "items", List.of(Map.of("productoId", p, "cantidad", 2)), "formaPago", "TARJETA"), 201);
        assertThat(get("/api/v1/productos?q=" + codigo, tk, 200).get("items").get(0).get("stockVendible").decimalValue()).isEqualByComparingTo("3");
        get("/actuator/health", null, 200);        // la salud de la aplicación no depende de Redis
    }
}
