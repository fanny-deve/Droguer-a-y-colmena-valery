package co.valery.pos.usuarios;

import co.valery.pos.auditoria.AuditService;
import co.valery.pos.common.Rol;
import co.valery.pos.config.AppProperties;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Primer arranque: si no existe ninguna administradora, la crea desde variables de entorno (nunca desde el
 * repositorio) y le exige cambiar la contraseña al ingresar.
 */
@Component
public class AdminBootstrapRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final UsuarioRepository usuarios;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final AuditService audit;
    private final AppProperties props;
    private final TransactionTemplate tx;

    public AdminBootstrapRunner(UsuarioRepository usuarios, PasswordEncoder encoder, PasswordPolicy policy,
                                AuditService audit, AppProperties props, TransactionTemplate tx) {
        this.usuarios = usuarios;
        this.encoder = encoder;
        this.policy = policy;
        this.audit = audit;
        this.props = props;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (usuarios.contarAdminsActivos() > 0) {
            return;
        }
        var cfg = props.adminBootstrap();
        if (cfg == null || cfg.username() == null || cfg.username().isBlank() || cfg.password() == null || cfg.password().isBlank()) {
            log.warn("No existe ninguna administradora y no se definieron ADMIN_USERNAME / ADMIN_INITIAL_PASSWORD: "
                    + "nadie podrá gestionar el sistema hasta crearla.");
            return;
        }
        String username = cfg.username().trim().toLowerCase(Locale.ROOT);
        policy.validar(cfg.password(), username);
        tx.executeWithoutResult(s -> {
            UUID id = usuarios.insertar(username, cfg.nombre() == null || cfg.nombre().isBlank() ? "Administradora" : cfg.nombre(),
                    null, null, encoder.encode(cfg.password()), Rol.ADMIN, true, null);
            audit.registrarComo(id, username, Rol.ADMIN.name(), "BOOTSTRAP_ADMIN", "usuarios", id.toString(),
                    Map.of("origen", "variables_de_entorno"), "OK");
        });
        log.info("Administradora inicial '{}' creada; deberá cambiar su contraseña al primer ingreso.", username);
    }
}
