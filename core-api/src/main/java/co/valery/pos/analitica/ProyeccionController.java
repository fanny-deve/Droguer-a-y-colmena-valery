package co.valery.pos.analitica;

import co.valery.pos.common.PageResponse;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/analitica/proyecciones")
@PreAuthorize("hasRole('ADMIN')")
public class ProyeccionController {
    private final ProyeccionService servicio;

    public ProyeccionController(ProyeccionService servicio) {
        this.servicio = servicio;
    }

    @PostMapping("/generar")
    public ProyeccionService.ResultadoGeneracion generar(@RequestBody(required = false) ProyeccionService.GenerarRequest r) {
        return servicio.generar(r);
    }

    @GetMapping("/estado")
    public ProyeccionService.EstadoAnalitica estado() {
        return servicio.estado();
    }

    @GetMapping
    public PageResponse<ProyeccionService.FilaProyeccion> listar(@RequestParam(defaultValue = "0") int page,
                                                                 @RequestParam(defaultValue = "50") int size) {
        return servicio.listarUltima(page, size);
    }

    @GetMapping("/{productoId}")
    public ProyeccionService.ProyeccionProductoDto producto(@PathVariable UUID productoId) {
        return servicio.consultar(productoId);
    }
}
