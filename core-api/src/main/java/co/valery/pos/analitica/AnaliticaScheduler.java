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
    private final ProyeccionService proyecciones;

    public AnaliticaScheduler(AbcService abc, ProyeccionService proyecciones) {
        this.abc = abc;
        this.proyecciones = proyecciones;
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

    /** Proyección semanal automática (lunes 4:00). Si el microservicio falla, solo se registra el error. */
    @Scheduled(cron = "0 0 4 * * MON", zone = "America/Bogota")
    public void generarProyecciones() {
        try {
            var r = proyecciones.generarSiLibre();
            if (r != null) log.info("Proyecciones generadas: {} productos", r.productos());
        } catch (RuntimeException e) {
            log.warn("No se generaron las proyecciones semanales: {}", e.getMessage());
        }
    }
}
