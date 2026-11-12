package co.valery.pos.usuarios;

import co.valery.pos.common.PageResponse;
import co.valery.pos.usuarios.UsuarioDtos.ActualizarCajeraRequest;
import co.valery.pos.usuarios.UsuarioDtos.CredencialTemporalResponse;
import co.valery.pos.usuarios.UsuarioDtos.CrearCajeraRequest;
import co.valery.pos.usuarios.UsuarioDtos.EliminarRequest;
import co.valery.pos.usuarios.UsuarioDtos.MotivoRequest;
import co.valery.pos.usuarios.UsuarioDtos.UsuarioDto;
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

/** Gestión de cajeras: exclusivo de la administradora (doble barrera: regla de URL y @PreAuthorize). */
@RestController
@RequestMapping("/api/v1/admin/usuarios")
@PreAuthorize("hasRole('ADMIN')")
public class UsuarioAdminController {
    private final UsuarioAdminService servicio;

    public UsuarioAdminController(UsuarioAdminService servicio) {
        this.servicio = servicio;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CredencialTemporalResponse crear(@Valid @RequestBody CrearCajeraRequest r) {
        return servicio.crearCajera(r);
    }

    @GetMapping
    public PageResponse<UsuarioDto> listar(@RequestParam(required = false) String estado,
                                           @RequestParam(required = false) String q,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "50") int size) {
        return servicio.listar(estado, q, page, size);
    }

    @GetMapping("/{id}")
    public UsuarioDto obtener(@PathVariable UUID id) {
        return servicio.obtener(id);
    }

    @PutMapping("/{id}")
    public UsuarioDto actualizar(@PathVariable UUID id, @Valid @RequestBody ActualizarCajeraRequest r) {
        return servicio.actualizar(id, r);
    }

    @PostMapping("/{id}/desactivar")
    public UsuarioDto desactivar(@PathVariable UUID id, @Valid @RequestBody MotivoRequest r) {
        return servicio.desactivar(id, r.motivo());
    }

    @PostMapping("/{id}/reactivar")
    public CredencialTemporalResponse reactivar(@PathVariable UUID id) {
        return servicio.reactivar(id);
    }

    @PostMapping("/{id}/reset-password")
    public CredencialTemporalResponse resetPassword(@PathVariable UUID id) {
        return servicio.resetearPassword(id);
    }

    @PostMapping("/{id}/desbloquear")
    public UsuarioDto desbloquear(@PathVariable UUID id) {
        return servicio.desbloquear(id);
    }

    @PostMapping("/{id}/eliminar")
    public UsuarioDto eliminar(@PathVariable UUID id, @Valid @RequestBody EliminarRequest r) {
        return servicio.eliminar(id, r);
    }
}
