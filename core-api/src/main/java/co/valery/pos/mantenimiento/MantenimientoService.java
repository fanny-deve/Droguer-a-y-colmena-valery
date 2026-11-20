package co.valery.pos.mantenimiento;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Limpieza diaria de datos operativos que ya no sirven. La auditoría, el kardex y las ventas NUNCA se purgan (son inmutables). */
@Component
public class MantenimientoService {
    private static final Logger log = LoggerFactory.getLogger(MantenimientoService.class);
    private final JdbcClient jdbc;

    public MantenimientoService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Elimina tokens de refresco vencidos o revocados hace más de 30 días. Devuelve cuántos borró. */
    @Transactional
    public int purgarTokens() {
        return jdbc.sql("""
                delete from refresh_tokens
                where (revocado_en is not null and revocado_en < now() - interval '30 days')
                   or expira_en < now() - interval '30 days'
                """).update();
    }

    @Scheduled(cron = "0 10 3 * * *", zone = "America/Bogota")
    public void diario() {
        try {
            int n = purgarTokens();   // idempotente: si dos instancias coinciden, la segunda simplemente no encuentra nada
            log.info("Mantenimiento diario: {} tokens de refresco purgados", n);
        } catch (RuntimeException e) {
            log.error("Falló el mantenimiento diario", e);
        }
    }
}
