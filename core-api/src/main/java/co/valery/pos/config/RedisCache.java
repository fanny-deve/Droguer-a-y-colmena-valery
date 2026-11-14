package co.valery.pos.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Caché de lectura en Redis Cloud (RNF-01: consultas de inventario y proyecciones). Es OPCIONAL y tolerante a fallos:
 * si Redis está deshabilitado o falla, todo opera directamente contra PostgreSQL y un "cortacircuitos" evita
 * insistir durante 30 s. La fuente de verdad siempre es la base de datos: las ventas NUNCA leen de la caché.
 * La invalidación es por versión de espacio de nombres (INCR), así que no requiere borrar claves una a una.
 */
@Component
public class RedisCache {
    private static final Logger log = LoggerFactory.getLogger(RedisCache.class);
    private static final long PAUSA_MS = 30_000;

    private final ObjectProvider<StringRedisTemplate> redis;
    private final ObjectMapper om;
    private final boolean habilitado;
    private volatile long pausadoHasta = 0;

    public RedisCache(ObjectProvider<StringRedisTemplate> redis, ObjectMapper om, AppProperties props) {
        this.redis = redis;
        this.om = om;
        this.habilitado = props.redis() != null && props.redis().enabled();
    }

    private boolean activo() {
        return habilitado && System.currentTimeMillis() >= pausadoHasta;
    }

    private void falla(RuntimeException e) {
        pausadoHasta = System.currentTimeMillis() + PAUSA_MS;
        log.warn("Redis no disponible; se usa solo PostgreSQL durante 30 s ({})", e.getClass().getSimpleName());
    }

    public long version(String espacio) {
        if (!activo()) return 0;
        try {
            String v = redis.getObject().opsForValue().get("ver:" + espacio);
            return v == null ? 0 : Long.parseLong(v);
        } catch (RuntimeException e) {
            falla(e);
            return 0;
        }
    }

    /** Invalida todo el espacio de nombres incrementando su versión. */
    public void invalidar(String espacio) {
        if (!activo()) return;
        try {
            redis.getObject().opsForValue().increment("ver:" + espacio);
        } catch (RuntimeException e) {
            falla(e);
        }
    }

    public <T> Optional<T> leer(String clave, TypeReference<T> tipo) {
        if (!activo()) return Optional.empty();
        try {
            String json = redis.getObject().opsForValue().get(clave);
            return json == null ? Optional.empty() : Optional.of(om.readValue(json, tipo));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        } catch (RuntimeException e) {
            falla(e);
            return Optional.empty();
        }
    }

    public void guardar(String clave, Object valor, Duration ttl) {
        if (!activo()) return;
        try {
            redis.getObject().opsForValue().set(clave, om.writeValueAsString(valor), ttl);
        } catch (JsonProcessingException e) {
            log.debug("No se pudo serializar para caché: {}", clave);
        } catch (RuntimeException e) {
            falla(e);
        }
    }
}
