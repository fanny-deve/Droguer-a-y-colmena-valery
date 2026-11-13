package co.valery.pos.analitica;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Recalcula la clasificación ABC cada semana (RF-14: "periódicamente"), sin intervención manual. */
@Component
public class AnaliticaScheduler {
    private static final Logger log = LoggerFactory.getLogger(AnaliticaScheduler.class);
    private final AbcService abc;

    public AnaliticaScheduler(AbcService abc) {
        this.abc = abc;
    }

    @Scheduled(cron = "0 30 3 * * MON", zone = "America/Bogota")
    public void recalcularAbc() {
        try {
            var r = abc.calcularSiLibre();
            if (r != null) log.info("ABC recalculada: {}", r.porCategoria());
        } catch (RuntimeException e) {
            log.error("Falló el recálculo semanal de la clasificación ABC", e);
        }
    }
}
