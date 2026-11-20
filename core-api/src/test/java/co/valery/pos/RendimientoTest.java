package co.valery.pos;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Medición de rendimiento con un volumen representativo (RNF-01, RNF-02): 3.000 productos, 6.000 lotes, ~100.000 filas de histórico
 * y 12.000 proyecciones almacenadas. Las consultas frecuentes deben responder en menos de 500 ms (percentil 95) con 8 usuarios simultáneos.
 * Los resultados se imprimen con la marca [RENDIMIENTO] para que la integración continua los publique.
 */
class RendimientoTest extends IntegrationTestBase {
    private static final String ADM = "/api/v1/admin";
    private static final long LIMITE_CONSULTA_MS = 500;
    private static final long LIMITE_VENTA_MS = 1500;

    private record Medida(String nombre, long p50, long p95, long p99, long max, int n, int errores) {}

    private Medida medir(String nombre, int total, int hilos, Callable<Integer> peticion) throws Exception {
        return medir(nombre, total, hilos, 20, peticion);
    }

    private Medida medir(String nombre, int total, int hilos, int calentamiento, Callable<Integer> peticion) throws Exception {
        for (int i = 0; i < calentamiento; i++) peticion.call();
        ExecutorService ex = Executors.newFixedThreadPool(hilos);
        List<Future<long[]>> fs = new ArrayList<>();
        AtomicInteger errores = new AtomicInteger();
        for (int i = 0; i < total; i++) {
            fs.add(ex.submit(() -> {
                long t0 = System.nanoTime();
                int status = peticion.call();
                if (status >= 400) errores.incrementAndGet();
                return new long[]{(System.nanoTime() - t0) / 1_000_000};
            }));
        }
        List<Long> t = new ArrayList<>();
        for (Future<long[]> f : fs) t.add(f.get()[0]);
        ex.shutdown();
        Collections.sort(t);
        Medida m = new Medida(nombre, t.get(t.size() / 2), t.get((int) (t.size() * 0.95) - 1), t.get((int) (t.size() * 0.99) - 1),
                t.get(t.size() - 1), t.size(), errores.get());
        System.out.printf("[RENDIMIENTO] %-34s n=%d hilos=%d p50=%dms p95=%dms p99=%dms max=%dms errores=%d%n",
                m.nombre(), m.n(), hilos, m.p50(), m.p95(), m.p99(), m.max(), m.errores());
        return m;
    }

    private int get(String url, String token) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url)
                .header("Authorization", "Bearer " + token)).andReturn().getResponse().getStatus();
    }

    @Test
    void lasConsultasFrecuentesRespondenEnMenosDe500msConUnVolumenRepresentativo() throws Exception {
        // ---- Volumen representativo (SQL en bloque) ----
        jdbc.sql("""
                insert into productos(codigo, nombre, categoria_id, unidad, precio_venta, costo_referencia, maneja_vencimiento, stock_minimo, punto_reorden)
                select 'PF' || lpad(g::text, 6, '0'),
                       'Producto de prueba ' || g || ' ' || (array['analgésico', 'jabón', 'miel', 'vitamina', 'crema'])[1 + g % 5],
                       (select min(id) from categorias), 'UNIDAD', 1000 + g % 5000, 500 + g % 2500, true, 5, 10
                from generate_series(1, 3000) g
                """).update();
        jdbc.sql("""
                insert into lotes(producto_id, codigo_lote, fecha_vencimiento, cantidad_inicial, cantidad_disponible, costo_unitario)
                select p.id, l.cod, current_date + 150 + (random() * 200)::int, 500, 500, 500
                from productos p cross join (values ('LF1'), ('LF2')) as l(cod) where p.codigo like 'PF%'
                """).update();
        jdbc.sql("""
                insert into historico_ventas(producto_id, fecha, unidades, ingresos)
                select p.id, current_date - d, 1 + (random() * 8)::int, 5000 from productos p cross join generate_series(1, 34) d where p.codigo like 'PF%'
                """).update();
        jdbc.sql("""
                insert into proyecciones_demanda(corrida_id, producto_id, periodo_inicio, periodo_fin, demanda_proyectada, modelo)
                select '00000000-0000-0000-0000-00000000f001'::uuid, p.id, current_date + 7 * w, current_date + 7 * w + 6, 5, 'STUB'
                from productos p cross join generate_series(0, 3) w where p.codigo like 'PF%'
                """).update();
        jdbc.sql("analyze").update();
        String productoMuestra = jdbc.sql("select id::text from productos where codigo = 'PF000777'").query(String.class).single();

        String cajera = tokenCajeraHabilitada(crearCajera());
        post("/api/v1/caja/abrir", cajera, Map.of("fondoInicial", 1000), 201);
        String admin = adminToken();

        // ---- RNF-01: consultas frecuentes de existencias y predicciones almacenadas ----
        List<Medida> consultas = new ArrayList<>();
        consultas.add(medir("busqueda de productos por nombre", 300, 8, () -> get("/api/v1/productos?q=jab%C3%B3n&size=30", cajera)));
        consultas.add(medir("consulta por codigo de barras", 300, 8, () -> get("/api/v1/productos/codigo/PF000777", cajera)));
        consultas.add(medir("listado de existencias (admin)", 200, 8, () -> get(ADM + "/productos?size=50&q=miel", admin)));
        consultas.add(medir("proyeccion almacenada de un producto", 300, 8, () -> get(ADM + "/analitica/proyecciones/" + productoMuestra, admin)));
        consultas.add(medir("resumen de alertas", 200, 8, () -> get(ADM + "/alertas/resumen", admin)));

        // ---- RNF-02: registro de ventas (cada venta descuenta FEFO, kardex y auditoría) ----
        AtomicInteger seq = new AtomicInteger(1000);
        Medida venta = medir("registro de una venta (FEFO)", 120, 4, () -> {
            String id = jdbc.sql("select id::text from productos where codigo = 'PF' || lpad(:n::text, 6, '0')").param("n", seq.incrementAndGet()).query(String.class).single();
            return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/ventas")
                    .header("Authorization", "Bearer " + cajera).contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsString(Map.of("idempotencyKey", UUID.randomUUID().toString(),
                            "items", List.of(Map.of("productoId", id, "cantidad", 1)), "formaPago", "TARJETA"))))
                    .andReturn().getResponse().getStatus();
        });

        // ---- Operaciones pesadas de la administradora (informativas con tope generoso) ----
        Medida abc = medir("calculo de clasificacion ABC (3.000)", 3, 1, 0, () -> mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(ADM + "/analitica/abc/calcular")
                        .header("Authorization", "Bearer " + admin)).andReturn().getResponse().getStatus());
        Medida sugerencias = medir("sugerencias de reabastecimiento (3.000)", 5, 1, 1, () -> get(ADM + "/reabastecimiento/sugerencias", admin));
        Medida valorizado = medir("inventario valorizado (6.000 lotes)", 5, 1, 1, () -> get(ADM + "/reportes/inventario/valorizado?size=100", admin));

        for (Medida m : consultas) {
            assertThat(m.errores()).as("errores en " + m.nombre()).isZero();
            assertThat(m.p95()).as("p95 de " + m.nombre()).isLessThan(LIMITE_CONSULTA_MS);
        }
        assertThat(venta.errores()).isZero();
        assertThat(venta.p95()).as("p95 del registro de venta").isLessThan(LIMITE_VENTA_MS);
        for (Medida m : List.of(abc, sugerencias, valorizado)) {
            assertThat(m.errores()).as("errores en " + m.nombre()).isZero();
            assertThat(m.max()).as("máximo de " + m.nombre()).isLessThan(10_000);
        }
    }
}
