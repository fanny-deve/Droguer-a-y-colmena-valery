package co.valery.pos.ventas;

import co.valery.pos.common.PageResponse;
import co.valery.pos.ventas.VentaDtos.AbrirCajaRequest;
import co.valery.pos.ventas.VentaDtos.AnularRequest;
import co.valery.pos.ventas.VentaDtos.CerrarCajaRequest;
import co.valery.pos.ventas.VentaDtos.CierreForzadoRequest;
import co.valery.pos.ventas.VentaDtos.ComprobanteDto;
import co.valery.pos.ventas.VentaDtos.RechazoSyncDto;
import co.valery.pos.ventas.VentaDtos.ResolverRechazoRequest;
import co.valery.pos.ventas.VentaDtos.SesionCajaDto;
import co.valery.pos.ventas.VentaDtos.SyncRequest;
import co.valery.pos.ventas.VentaDtos.SyncResponse;
import co.valery.pos.ventas.VentaDtos.VentaDto;
import co.valery.pos.ventas.VentaDtos.VentaRequest;
import co.valery.pos.ventas.VentaDtos.VentaResumenDto;
import co.valery.pos.ventas.VentaDtos.VerificacionSello;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

public final class VentaControllers {
    private VentaControllers() {}

    /** Ventas: cajera (solo las propias) y administradora (todas). */
    @RestController
    @RequestMapping("/api/v1/ventas")
    public static class VentaController {
        private final VentaService ventas;
        private final SyncVentasService sync;

        public VentaController(VentaService ventas, SyncVentasService sync) {
            this.ventas = ventas;
            this.sync = sync;
        }

        /** 201 si es nueva; 200 si la clave de idempotencia ya existía (reintento seguro). */
        @PostMapping
        public ResponseEntity<VentaDto> registrar(@Valid @RequestBody VentaRequest r) {
            VentaService.Resultado res = ventas.registrar(r, "ONLINE");
            return ResponseEntity.status(res.duplicada() ? HttpStatus.OK : HttpStatus.CREATED).body(res.venta());
        }

        @GetMapping
        public PageResponse<VentaResumenDto> listar(@RequestParam(required = false) UUID cajeroId,
                                                    @RequestParam(required = false) String estado,
                                                    @RequestParam(required = false) String formaPago,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "30") int size) {
            return ventas.listar(cajeroId, estado, formaPago, desde, hasta, page, size);
        }

        @GetMapping("/{id}")
        public VentaDto obtener(@PathVariable UUID id) {
            return ventas.obtener(id);
        }

        @GetMapping("/{id}/comprobante")
        public ComprobanteDto comprobante(@PathVariable UUID id) {
            return ventas.comprobante(id);
        }

        @PostMapping("/sincronizar")
        public SyncResponse sincronizar(@Valid @RequestBody SyncRequest r) {
            return sync.sincronizar(r);
        }

        @GetMapping("/sincronizacion/rechazos")
        public PageResponse<RechazoSyncDto> rechazos(@RequestParam(required = false) String estado,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "30") int size) {
            return sync.listarRechazos(estado, page, size);
        }
    }

    /** Caja propia de quien está autenticado. */
    @RestController
    @RequestMapping("/api/v1/caja")
    public static class CajaController {
        private final CajaService caja;

        public CajaController(CajaService caja) {
            this.caja = caja;
        }

        @PostMapping("/abrir")
        @ResponseStatus(HttpStatus.CREATED)
        public SesionCajaDto abrir(@Valid @RequestBody AbrirCajaRequest r) {
            return caja.abrir(r);
        }

        @GetMapping("/actual")
        public SesionCajaDto actual() {
            return caja.actual();
        }

        @PostMapping("/cerrar")
        public SesionCajaDto cerrar(@Valid @RequestBody CerrarCajaRequest r) {
            return caja.cerrar(r);
        }
    }

    @RestController
    @RequestMapping("/api/v1/admin/ventas")
    @PreAuthorize("hasRole('ADMIN')")
    public static class VentaAdminController {
        private final VentaService ventas;

        public VentaAdminController(VentaService ventas) {
            this.ventas = ventas;
        }

        @PostMapping("/{id}/anular")
        public VentaDto anular(@PathVariable UUID id, @Valid @RequestBody AnularRequest r) {
            return ventas.anular(id, r);
        }

        @GetMapping("/{id}/verificar-sello")
        public VerificacionSello verificar(@PathVariable UUID id) {
            return ventas.verificarSello(id);
        }
    }

    @RestController
    @RequestMapping("/api/v1/admin/caja")
    @PreAuthorize("hasRole('ADMIN')")
    public static class CajaAdminController {
        private final CajaService caja;

        public CajaAdminController(CajaService caja) {
            this.caja = caja;
        }

        @GetMapping("/sesiones")
        public PageResponse<SesionCajaDto> sesiones(@RequestParam(required = false) UUID cajeroId,
                                                    @RequestParam(required = false) String estado,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "30") int size) {
            return caja.listar(cajeroId, estado, desde, hasta, page, size);
        }

        @PostMapping("/sesiones/{id}/cerrar-forzado")
        public SesionCajaDto cerrarForzado(@PathVariable UUID id, @Valid @RequestBody CierreForzadoRequest r) {
            return caja.cerrarForzado(id, r);
        }
    }

    @RestController
    @RequestMapping("/api/v1/admin/sync/rechazos")
    @PreAuthorize("hasRole('ADMIN')")
    public static class SyncAdminController {
        private final SyncVentasService sync;

        public SyncAdminController(SyncVentasService sync) {
            this.sync = sync;
        }

        @GetMapping
        public PageResponse<RechazoSyncDto> listar(@RequestParam(required = false) String estado,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "30") int size) {
            return sync.listarRechazos(estado, page, size);
        }

        @PostMapping("/{id}/resolver")
        public ResponseEntity<Void> resolver(@PathVariable UUID id, @Valid @RequestBody ResolverRechazoRequest r) {
            sync.resolver(id, r.nota());
            return ResponseEntity.noContent().build();
        }
    }
}
