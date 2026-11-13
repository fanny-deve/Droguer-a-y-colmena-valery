package co.valery.pos.reabastecimiento;

import co.valery.pos.common.PageResponse;
import co.valery.pos.reabastecimiento.PedidoDtos.MotivoPedidoRequest;
import co.valery.pos.reabastecimiento.PedidoDtos.PedidoDto;
import co.valery.pos.reabastecimiento.PedidoDtos.PedidoRequest;
import co.valery.pos.reabastecimiento.PedidoDtos.PedidoResumenDto;
import co.valery.pos.reabastecimiento.PedidoDtos.RecepcionRequest;
import co.valery.pos.reabastecimiento.PedidoDtos.SugerenciasResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

public final class ReabastecimientoControllers {
    private ReabastecimientoControllers() {}

    @RestController
    @RequestMapping("/api/v1/admin/reabastecimiento")
    @PreAuthorize("hasRole('ADMIN')")
    public static class SugerenciaController {
        private final ReabastecimientoService servicio;

        public SugerenciaController(ReabastecimientoService servicio) {
            this.servicio = servicio;
        }

        @GetMapping("/sugerencias")
        public SugerenciasResponse sugerencias(@RequestParam(required = false) Integer cobertura,
                                               @RequestParam(required = false) UUID proveedorId) {
            return servicio.sugerir(cobertura, proveedorId);
        }
    }

    @RestController
    @RequestMapping("/api/v1/admin/pedidos")
    @PreAuthorize("hasRole('ADMIN')")
    public static class PedidoController {
        private final PedidoService pedidos;

        public PedidoController(PedidoService pedidos) {
            this.pedidos = pedidos;
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        public PedidoDto crear(@Valid @RequestBody PedidoRequest r) {
            return pedidos.crear(r);
        }

        @GetMapping
        public PageResponse<PedidoResumenDto> listar(@RequestParam(required = false) String estado,
                                                     @RequestParam(required = false) UUID proveedorId,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "30") int size) {
            return pedidos.listar(estado, proveedorId, page, size);
        }

        @GetMapping("/{id}")
        public PedidoDto obtener(@PathVariable UUID id) {
            return pedidos.obtener(id);
        }

        @PutMapping("/{id}")
        public PedidoDto actualizar(@PathVariable UUID id, @Valid @RequestBody PedidoRequest r) {
            return pedidos.actualizar(id, r);
        }

        @PostMapping("/{id}/enviar")
        public PedidoDto enviar(@PathVariable UUID id) {
            return pedidos.enviar(id);
        }

        @PostMapping("/{id}/recepcion")
        public PedidoDto recibir(@PathVariable UUID id, @Valid @RequestBody RecepcionRequest r) {
            return pedidos.recibir(id, r);
        }

        @PostMapping("/{id}/cancelar")
        public PedidoDto cancelar(@PathVariable UUID id, @Valid @RequestBody MotivoPedidoRequest r) {
            return pedidos.cancelar(id, r.motivo());
        }

        @PostMapping("/{id}/cerrar")
        public PedidoDto cerrar(@PathVariable UUID id, @Valid @RequestBody MotivoPedidoRequest r) {
            return pedidos.cerrarParcial(id, r.motivo());
        }
    }
}
