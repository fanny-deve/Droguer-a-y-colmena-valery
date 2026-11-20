package co.valery.pos.security;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Limitador de ventana fija por clave (IP) y minuto, en memoria. Suficiente para una instancia; sin dependencias externas. */
public final class RateLimiter {
    private record Ventana(long minuto, AtomicInteger conteo) {}

    private final ConcurrentHashMap<String, Ventana> ventanas = new ConcurrentHashMap<>();
    private final int limite;

    public RateLimiter(int limitePorMinuto) {
        this.limite = Math.max(limitePorMinuto, 1);
    }

    /** Devuelve los segundos a esperar si se excedió el límite, o 0 si la petición está permitida. */
    public int intentar(String clave, long ahoraMs) {
        long minuto = ahoraMs / 60_000;
        if (ventanas.size() > 20_000) ventanas.values().removeIf(v -> v.minuto() < minuto);
        Ventana v = ventanas.compute(clave, (k, actual) -> actual == null || actual.minuto() != minuto
                ? new Ventana(minuto, new AtomicInteger(0)) : actual);
        if (v.conteo().incrementAndGet() > limite) {
            return (int) Math.max(1, (minuto + 1) * 60 - ahoraMs / 1000);
        }
        return 0;
    }
}
