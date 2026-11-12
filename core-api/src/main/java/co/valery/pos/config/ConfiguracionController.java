package co.valery.pos.config;

import co.valery.pos.auditoria.AuditService;
import co.valery.pos.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Parámetros del sistema: solo la administradora, solo claves existentes y respetando el tipo del valor actual. */
@RestController
@RequestMapping("/api/v1/admin/configuracion")
@PreAuthorize("hasRole('ADMIN')")
public class ConfiguracionController {
    public record ActualizarParametro(@NotNull JsonNode valor) {}

    private final ConfiguracionService config;
    private final AuditService audit;
    private final ObjectMapper om;

    public ConfiguracionController(ConfiguracionService config, AuditService audit, ObjectMapper om) {
        this.config = config;
        this.audit = audit;
        this.om = om;
    }

    @GetMapping
    public List<ConfiguracionService.Parametro> listar() {
        return config.listar();
    }

    @PutMapping("/{clave}")
    @Transactional
    public ConfiguracionService.Parametro actualizar(@PathVariable String clave, @RequestBody ActualizarParametro r) throws Exception {
        if (r.valor() == null) throw ApiException.badRequest("VALOR_REQUERIDO", "Debe indicar el valor");
        String actualCrudo = config.crudo(clave)
                .orElseThrow(() -> ApiException.notFound("PARAMETRO_NO_ENCONTRADO", "No existe el parámetro " + clave));
        JsonNode actual = om.readTree(actualCrudo);
        if (actual.getNodeType() != r.valor().getNodeType()) {
            throw ApiException.badRequest("TIPO_INVALIDO", "El valor debe ser del mismo tipo que el actual (" + actual.getNodeType() + ")");
        }
        if (actual.isNumber() && r.valor().decimalValue().signum() < 0) {
            throw ApiException.badRequest("VALOR_INVALIDO", "El valor no puede ser negativo");
        }
        if (actual.isArray() && actual.size() != r.valor().size()) {
            throw ApiException.badRequest("VALOR_INVALIDO", "La lista debe conservar " + actual.size() + " elementos");
        }
        config.actualizar(clave, om.treeToValue(r.valor(), Object.class));
        audit.registrar("ACTUALIZAR_CONFIGURACION", "configuracion", clave, Map.of("antes", actual.toString(), "despues", r.valor().toString()));
        return config.listar().stream().filter(p -> p.clave().equals(clave)).findFirst().orElseThrow();
    }
}
