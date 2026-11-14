package co.valery.pos;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

/** Con Redis real: la caché acelera las lecturas pero nunca devuelve existencias desactualizadas tras un movimiento. */
class RedisCacheTest extends IntegrationTestBase {
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry r) {
        r.add("app.redis.enabled", () -> "true");
        r.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getFirstMappedPort());
    }

    @Autowired StringRedisTemplate redis;

    @Test
    void cacheaLasBusquedasDeProductosYLasInvalidaConCadaMovimientoDeInventario() throws Exception {
        String codigo = "RC" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        int cat = jdbc.sql("select id from categorias order by id limit 1").query(Integer.class).single();
        Map<String, Object> b = new HashMap<>();
        b.put("codigo", codigo);
        b.put("nombre", "Producto " + codigo);
        b.put("categoriaId", cat);
        b.put("unidad", "UNIDAD");
        b.put("precioVenta", 3000);
        b.put("costoReferencia", 1500);
        String p = post("/api/v1/admin/productos", adminToken(), b, 201).get("id").asText();
        post("/api/v1/admin/inventario/entradas", adminToken(), Map.of("productoId", p, "codigoLote", "C1",
                "fechaVencimiento", LocalDate.now().plusDays(100).toString(), "cantidad", 10, "costoUnitario", 1000), 201);
        String tk = tokenCajeraHabilitada(crearCajera());
        post("/api/v1/caja/abrir", tk, Map.of("fondoInicial", 1000), 201);

        JsonNode antes = get("/api/v1/productos?q=" + codigo, tk, 200);
        assertThat(antes.get("items").get(0).get("stockVendible").decimalValue()).isEqualByComparingTo("10");
        assertThat(redis.keys("prod:*")).isNotEmpty();                          // quedó en caché
        assertThat(get("/api/v1/productos?q=" + codigo, tk, 200)).isEqualTo(antes);   // y se sirve igual

        post("/api/v1/ventas", tk, Map.of("idempotencyKey", UUID.randomUUID().toString(),
                "items", List.of(Map.of("productoId", p, "cantidad", 3)), "formaPago", "TARJETA"), 201);
        JsonNode despues = get("/api/v1/productos?q=" + codigo, tk, 200);
        assertThat(despues.get("items").get(0).get("stockVendible").decimalValue()).isEqualByComparingTo("7");   // sin datos viejos

        // Un cambio de precio también invalida
        put("/api/v1/admin/productos/" + p, adminToken(), Map.of("nombre", "Producto " + codigo, "categoriaId", cat, "unidad", "UNIDAD",
                "precioVenta", 3500, "costoReferencia", 1500), 200);
        assertThat(get("/api/v1/productos?q=" + codigo, tk, 200).get("items").get(0).get("precioVenta").decimalValue()).isEqualByComparingTo("3500");
    }

    @Test
    void cacheaLasProyeccionesConsultadas() throws Exception {
        String codigo = "RP" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        int cat = jdbc.sql("select id from categorias order by id limit 1").query(Integer.class).single();
        String p = post("/api/v1/admin/productos", adminToken(), Map.of("codigo", codigo, "nombre", "Producto " + codigo, "categoriaId", cat,
                "unidad", "UNIDAD", "precioVenta", 3000, "costoReferencia", 1500), 201).get("id").asText();
        jdbc.sql("insert into historico_ventas(producto_id, fecha, unidades, ingresos) values (cast(:p as uuid), current_date - 10, 5, 15000)")
                .param("p", p).update();
        post("/api/v1/admin/analitica/proyecciones/generar", adminToken(), Map.of("productoIds", List.of(p)), 200);
        JsonNode a = get("/api/v1/admin/analitica/proyecciones/" + p, adminToken(), 200);
        assertThat(redis.keys("proy:*")).isNotEmpty();
        assertThat(get("/api/v1/admin/analitica/proyecciones/" + p, adminToken(), 200)).isEqualTo(a);
    }
}
