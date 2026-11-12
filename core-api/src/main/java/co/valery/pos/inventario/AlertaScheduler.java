package co.valery.pos.inventario;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Re-evalúa vencimientos cada hora (los lotes cambian de umbral con el paso de los días, sin intervención manual). */
@Component
public class AlertaScheduler {
    private static final Logger log = LoggerFactory.getLogger(AlertaScheduler.class);
    private final AlertaService alertas;

    public AlertaScheduler(AlertaService alertas) {
        this.alertas = alertas;
    }

    @Scheduled(cron = "0 5 * * * *", zone = "America/Bogota")
    public void evaluar() {
        try {
            var r = alertas.evaluarTodoSiLibre();
            log.info("Evaluación periódica de alertas: {} creadas, {} resueltas", r.creadas(), r.resueltas());
        } catch (RuntimeException e) {
            log.error("Falló la evaluación periódica de alertas", e);
        }
    }
}
