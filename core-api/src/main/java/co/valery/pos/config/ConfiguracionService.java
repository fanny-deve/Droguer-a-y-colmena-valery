package co.valery.pos.config;

import co.valery.pos.security.CurrentUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Parámetros del sistema almacenados en la tabla `configuracion` (umbrales de alertas, ABC, seguridad). */
@Service
public class ConfiguracionService {
    private final JdbcClient jdbc;
    private final ObjectMapper om;

    public ConfiguracionService(JdbcClient jdbc, ObjectMapper om) {
        this.jdbc = jdbc;
        this.om = om;
    }

    public Optional<String> crudo(String clave) {
        return jdbc.sql("select valor::text from configuracion where clave = :c").param("c", clave)
                .query((rs, i) -> rs.getString(1)).optional();
    }

    public int getInt(String clave, int porDefecto) {
        return crudo(clave).map(v -> {
            try {
                return om.readTree(v).asInt(porDefecto);
            } catch (JsonProcessingException e) {
                return porDefecto;
            }
        }).orElse(porDefecto);
    }

    public BigDecimal getDecimal(String clave, BigDecimal porDefecto) {
        return crudo(clave).map(v -> {
            try {
                return om.readTree(v).decimalValue();
            } catch (JsonProcessingException e) {
                return porDefecto;
            }
        }).orElse(porDefecto);
    }

    public String getString(String clave, String porDefecto) {
        return crudo(clave).map(v -> {
            try {
                return om.readTree(v).asText(porDefecto);
            } catch (JsonProcessingException e) {
                return porDefecto;
            }
        }).orElse(porDefecto);
    }

    public List<Integer> getIntList(String clave, List<Integer> porDefecto) {
        return crudo(clave).map(v -> {
            try {
                return om.readValue(v, new TypeReference<List<Integer>>() {});
            } catch (JsonProcessingException e) {
                return porDefecto;
            }
        }).orElse(porDefecto);
    }

    public record Parametro(String clave, Object valor, String descripcion) {}

    public List<Parametro> listar() {
        return jdbc.sql("select clave, valor::text as valor, descripcion from configuracion order by clave")
                .query((rs, i) -> {
                    Object valor;
                    try {
                        valor = om.readValue(rs.getString("valor"), Object.class);
                    } catch (JsonProcessingException e) {
                        valor = rs.getString("valor");
                    }
                    return new Parametro(rs.getString("clave"), valor, rs.getString("descripcion"));
                }).list();
    }

    /** Actualiza un parámetro existente (no crea claves nuevas). Devuelve false si la clave no existe. */
    public boolean actualizar(String clave, Object valor) {
        String json;
        try {
            json = om.writeValueAsString(valor);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Valor no serializable", e);
        }
        return jdbc.sql("""
                update configuracion set valor = cast(:v as jsonb), actualizado_por = cast(:u as uuid), actualizado_en = now()
                where clave = :c
                """).param("v", json).param("c", clave)
                .param("u", CurrentUser.opt().map(a -> a.id()).orElse(null)).update() > 0;
    }
}
