package co.valery.pos.usuarios;

import co.valery.pos.auditoria.AuditService;
import co.valery.pos.common.ApiException;
import co.valery.pos.common.PageResponse;
import co.valery.pos.common.Rol;
import co.valery.pos.security.AuthUser;
import co.valery.pos.security.CurrentUser;
import co.valery.pos.usuarios.UsuarioDtos.ActualizarCajeraRequest;
import co.valery.pos.usuarios.UsuarioDtos.CredencialTemporalResponse;
import co.valery.pos.usuarios.UsuarioDtos.CrearCajeraRequest;
import co.valery.pos.usuarios.UsuarioDtos.EliminarRequest;
import co.valery.pos.usuarios.UsuarioDtos.UsuarioDto;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gestión de cajeras por la administradora (RF-19). Cada operación valida el estado de la cuenta, exige motivo
 * cuando es destructiva, revoca sesiones y queda en la bitácora (RF-20). Solo se administran cuentas CAJERO.
 */
@Service
public class UsuarioAdminService {
    private static final String AVISO_TEMPORAL =
            "Entregue esta contraseña temporal en persona. No se vuelve a mostrar y debe cambiarse en el primer ingreso.";

    private final UsuarioRepository usuarios;
    private final RefreshTokenRepository refresh;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final AuditService audit;

    public UsuarioAdminService(UsuarioRepository usuarios, RefreshTokenRepository refresh, PasswordEncoder encoder,
                               PasswordPolicy policy, AuditService audit) {
        this.usuarios = usuarios;
        this.refresh = refresh;
        this.encoder = encoder;
        this.policy = policy;
        this.audit = audit;
    }

    @Transactional
    public CredencialTemporalResponse crearCajera(CrearCajeraRequest r) {
        AuthUser admin = CurrentUser.require();
        String username = r.username().trim().toLowerCase(Locale.ROOT);
        if (usuarios.findByUsername(username).isPresent()) {
            throw ApiException.conflict("USERNAME_EN_USO", "Ya existe una cuenta con ese nombre de usuario");
        }
        String temporal = policy.generarTemporal();
        UUID id = usuarios.insertar(username, r.nombreCompleto().trim(), limpiarEmail(r.email()), limpiar(r.telefono()),
                encoder.encode(temporal), Rol.CAJERO, true, admin.id());
        audit.registrar("CREAR_USUARIO", "usuarios", id.toString(), Map.of("username", username, "rol", "CAJERO"));
        return new CredencialTemporalResponse(UsuarioDto.de(usuarios.findById(id).orElseThrow()), temporal, AVISO_TEMPORAL);
    }

    @Transactional(readOnly = true)
    public PageResponse<UsuarioDto> listar(String estado, String q, int page, int size) {
        String est = estado == null || estado.isBlank() ? null : estado.trim().toUpperCase(Locale.ROOT);
        if (est != null && !est.equals("ACTIVO") && !est.equals("INACTIVO") && !est.equals("ELIMINADO")) {
            throw ApiException.badRequest("ESTADO_INVALIDO", "Estado válido: ACTIVO, INACTIVO o ELIMINADO");
        }
        int tam = PageResponse.limitarTamano(size);
        int pag = Math.max(page, 0);
        var filtro = new UsuarioRepository.Filtro(est, q == null || q.isBlank() ? null : q.trim(), Rol.CAJERO);
        var items = usuarios.listar(filtro, tam, pag * tam).stream().map(UsuarioDto::de).toList();
        return new PageResponse<>(items, pag, tam, usuarios.contar(filtro));
    }

    @Transactional(readOnly = true)
    public UsuarioDto obtener(UUID id) {
        return UsuarioDto.de(cajera(id, false));
    }

    @Transactional
    public UsuarioDto actualizar(UUID id, ActualizarCajeraRequest r) {
        Usuario antes = cajera(id, true);
        noEliminada(antes);
        usuarios.actualizarDatos(id, r.nombreCompleto().trim(), limpiarEmail(r.email()), limpiar(r.telefono()));
        Map<String, Object> detalle = new HashMap<>();
        detalle.put("username", antes.username());
        detalle.put("nombreAntes", antes.nombreCompleto());
        detalle.put("nombreDespues", r.nombreCompleto().trim());
        audit.registrar("ACTUALIZAR_USUARIO", "usuarios", id.toString(), detalle);
        return UsuarioDto.de(usuarios.findById(id).orElseThrow());
    }

    @Transactional
    public UsuarioDto desactivar(UUID id, String motivo) {
        AuthUser admin = CurrentUser.require();
        Usuario u = cajera(id, true);
        noEliminada(u);
        if (!u.activo()) throw ApiException.conflict("YA_INACTIVA", "La cuenta ya está desactivada");
        exigirCajaCerrada(u);
        usuarios.desactivar(id, admin.id(), motivo.trim());
        refresh.revocarTodosDelUsuario(id);
        audit.registrar("DESACTIVAR_USUARIO", "usuarios", id.toString(), Map.of("username", u.username(), "motivo", motivo.trim()));
        return UsuarioDto.de(usuarios.findById(id).orElseThrow());
    }

    @Transactional
    public CredencialTemporalResponse reactivar(UUID id) {
        Usuario u = cajera(id, true);
        noEliminada(u);
        if (u.activo()) throw ApiException.conflict("YA_ACTIVA", "La cuenta ya está activa");
        String temporal = policy.generarTemporal();
        usuarios.reactivar(id);
        usuarios.actualizarPassword(id, encoder.encode(temporal), true);
        refresh.revocarTodosDelUsuario(id);
        audit.registrar("REACTIVAR_USUARIO", "usuarios", id.toString(), Map.of("username", u.username()));
        return new CredencialTemporalResponse(UsuarioDto.de(usuarios.findById(id).orElseThrow()), temporal, AVISO_TEMPORAL);
    }

    @Transactional
    public CredencialTemporalResponse resetearPassword(UUID id) {
        Usuario u = cajera(id, true);
        noEliminada(u);
        if (!u.activo()) throw ApiException.conflict("CUENTA_INACTIVA", "Reactive la cuenta antes de restablecer su contraseña");
        String temporal = policy.generarTemporal();
        usuarios.actualizarPassword(id, encoder.encode(temporal), true);
        refresh.revocarTodosDelUsuario(id);
        audit.registrar("RESETEAR_PASSWORD", "usuarios", id.toString(), Map.of("username", u.username()));
        return new CredencialTemporalResponse(UsuarioDto.de(usuarios.findById(id).orElseThrow()), temporal, AVISO_TEMPORAL);
    }

    @Transactional
    public UsuarioDto desbloquear(UUID id) {
        Usuario u = cajera(id, true);
        noEliminada(u);
        usuarios.desbloquear(id);
        audit.registrar("DESBLOQUEAR_USUARIO", "usuarios", id.toString(), Map.of("username", u.username()));
        return UsuarioDto.de(usuarios.findById(id).orElseThrow());
    }

    /**
     * Eliminación definitiva (baja lógica irreversible): exige motivo y la contraseña de la administradora
     * (reautenticación). Conserva la identidad por trazabilidad, borra email/teléfono y revoca sesiones.
     */
    @Transactional
    public UsuarioDto eliminar(UUID id, EliminarRequest r) {
        AuthUser actor = CurrentUser.require();
        Usuario admin = usuarios.findById(actor.id())
                .orElseThrow(() -> ApiException.unauthorized("NO_AUTENTICADO", "Debe iniciar sesión"));
        if (!encoder.matches(r.passwordAdmin(), admin.passwordHash())) {
            audit.denegado(actor, "ELIMINAR_USUARIO_DENEGADO", "usuarios", id.toString(), Map.of("motivo", "reautenticacion_fallida"));
            throw ApiException.forbidden("PASSWORD_INCORRECTA", "La contraseña de confirmación no es correcta");
        }
        Usuario u = cajera(id, true);
        noEliminada(u);
        exigirCajaCerrada(u);
        usuarios.eliminar(id, actor.id(), r.motivo().trim());
        refresh.revocarTodosDelUsuario(id);
        audit.registrar("ELIMINAR_USUARIO", "usuarios", id.toString(), Map.of("username", u.username(),
                "nombreCompleto", u.nombreCompleto(), "estadoPrevio", u.estado(), "motivo", r.motivo().trim()));
        return UsuarioDto.de(usuarios.findById(id).orElseThrow());
    }

    private Usuario cajera(UUID id, boolean bloquear) {
        var opt = bloquear ? usuarios.findByIdForUpdate(id) : usuarios.findById(id);
        return opt.filter(x -> x.rol() == Rol.CAJERO)
                .orElseThrow(() -> ApiException.notFound("USUARIO_NO_ENCONTRADO", "No se encontró la cajera indicada"));
    }

    private static void noEliminada(Usuario u) {
        if (u.eliminadoEn() != null) {
            throw ApiException.conflict("USUARIO_ELIMINADO", "La cuenta fue eliminada y no admite más cambios");
        }
    }

    private void exigirCajaCerrada(Usuario u) {
        if (usuarios.sesionesCajaAbiertas(u.id()) > 0) {
            throw ApiException.conflict("CAJA_ABIERTA", "La cajera tiene una sesión de caja abierta; ciérrela antes de continuar");
        }
    }

    private static String limpiar(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String limpiarEmail(String s) {
        return s == null || s.isBlank() ? null : s.trim().toLowerCase(Locale.ROOT);
    }
}
