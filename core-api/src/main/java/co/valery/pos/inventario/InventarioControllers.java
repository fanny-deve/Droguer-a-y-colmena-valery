package co.valery.pos.inventario;

import co.valery.pos.common.PageResponse;
import co.valery.pos.inventario.InventarioDtos.AjusteDto;
import co.valery.pos.inventario.InventarioDtos.AjusteRequest;
import co.valery.pos.inventario.InventarioDtos.AlertaDto;
import co.valery.pos.inventario.InventarioDtos.BloqueoRequest;
import co.valery.pos.inventario.InventarioDtos.EntradaLoteRequest;
import co.valery.pos.inventario.InventarioDtos.LoteDto;
import co.valery.pos.inventario.InventarioDtos.MovimientoDto;
import co.valery.pos.inventario.InventarioDtos.ResultadoEvaluacion;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

public final class InventarioControllers {
    private InventarioControllers() {}

    @RestController
    @RequestMapping("/api/v1/admin/inventario")
    @PreAuthorize("hasRole('ADMIN')")
    public static class InventarioAdminController {
        private final InventarioService servicio;

        public InventarioAdminController(InventarioService servicio) {
            this.servicio = servicio;
        }

        @PostMapping("/entradas")
        @ResponseStatus(HttpStatus.CREATED)
        public LoteDto entrada(@Valid @RequestBody EntradaLoteRequest r) {
            return servicio.registrarEntrada(r);
        }

        @GetMapping("/lotes")
        public PageResponse<LoteDto> lotes(@RequestParam(required = false) UUID productoId,
                                           @RequestParam(required = false) String estado,
                                           @RequestParam(required = false) Integer vencenEnDias,
                                           @RequestParam(defaultValue = "true") boolean conStock,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "50") int size) {
            return servicio.listarLotes(productoId, estado, vencenEnDias, conStock, page, size);
        }

        @GetMapping("/lotes/{id}")
        public LoteDto lote(@PathVariable UUID id) {
            return servicio.obtenerLote(id);
        }

        @PostMapping("/lotes/{id}/bloquear")
        public LoteDto bloquear(@PathVariable UUID id, @Valid @RequestBody BloqueoRequest r) {
            return servicio.cambiarBloqueo(id, true, r.motivo());
        }

        @PostMapping("/lotes/{id}/desbloquear")
        public LoteDto desbloquear(@PathVariable UUID id, @Valid @RequestBody BloqueoRequest r) {
            return servicio.cambiarBloqueo(id, false, r.motivo());
        }

        @PostMapping("/ajustes")
        @ResponseStatus(HttpStatus.CREATED)
        public AjusteDto ajustar(@Valid @RequestBody AjusteRequest r) {
            return servicio.ajustar(r);
        }

        @GetMapping("/ajustes")
        public PageResponse<AjusteDto> ajustes(@RequestParam(required = false) UUID productoId,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "50") int size) {
            return servicio.listarAjustes(productoId, desde, hasta, page, size);
        }

        @GetMapping("/movimientos")
        public PageResponse<MovimientoDto> kardex(@RequestParam(required = false) UUID productoId,
                                                  @RequestParam(required = false) UUID loteId,
                                                  @RequestParam(required = false) String tipo,
                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "50") int size) {
            return servicio.kardex(productoId, loteId, tipo, desde, hasta, page, size);
        }
    }

    @RestController
    @RequestMapping("/api/v1/admin/alertas")
    @PreAuthorize("hasRole('ADMIN')")
    public static class AlertaAdminController {
        private final AlertaService alertas;

        public AlertaAdminController(AlertaService alertas) {
            this.alertas = alertas;
        }

        @GetMapping
        public PageResponse<AlertaDto> listar(@RequestParam(required = false) String estado,
                                              @RequestParam(required = false) String tipo,
                                              @RequestParam(required = false) UUID productoId,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "50") int size) {
            return alertas.listar(estado, tipo, productoId, page, size);
        }

        @GetMapping("/resumen")
        public Map<String, Long> resumen() {
            return alertas.resumen();
        }

        @PostMapping("/{id}/vista")
        public AlertaDto vista(@PathVariable UUID id) {
            return alertas.marcarVista(id);
        }

        @PostMapping("/evaluar")
        public ResultadoEvaluacion evaluar() {
            return alertas.evaluarTodo();
        }
    }
}
