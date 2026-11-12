package co.valery.pos.usuarios;

import static co.valery.pos.usuarios.UsuarioDtos.ChangePasswordRequest;
import static co.valery.pos.usuarios.UsuarioDtos.MeResponse;
import static co.valery.pos.usuarios.UsuarioDtos.TokenResponse;
import static co.valery.pos.usuarios.UsuarioDtos.UsuarioResumen;

import co.valery.pos.auditoria.AuditService;
import co.valery.pos.common.ApiException;
import co.valery.pos.config.AppProperties;
import co.valery.pos.config.ConfiguracionService;
import co.valery.pos.security.AuthUser;
import co.valery.pos.security.ClientInfo;
import co.valery.pos.security.CurrentUser;
import co.valery.pos.security.JwtService;
import co.valery.pos.security.TokenHasher;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UsuarioRepository usuarios;
    private final RefreshTokenRepository refresh;
    private final JwtService jwt;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final AuditService audit;
    private final ConfiguracionService config;
    private final AppProperties props;
    private final String hashSenuelo;

    public AuthService(UsuarioRepository usuarios, RefreshTokenRepository refresh, JwtService jwt, PasswordEncoder encoder,
                       PasswordPolicy policy, AuditService audit, ConfiguracionService config, AppProperties props) {
        this.usuarios = usuarios;
        this.refresh = refresh;
        this.jwt = jwt;
        this.encoder = encoder;
        this.policy = policy;
        this.audit = audit;
        this.config = config;
        this.props = props;
        // Se compara contra este hash cuando el usuario no existe, para que el tiempo de respuesta no delate la cuenta
        this.hashSenuelo = encoder.encode("senuelo-para-igualar-tiempos-Aa1#");
    }

    private record Emitido(TokenResponse respuesta, UUID refreshId) {}

    @Transactional(noRollbackFor = ApiException.class)
    public TokenResponse login(String usernameEntrada, String password, String userAgent) {
        String username = usernameEntrada.trim().toLowerCase(Locale.ROOT);
        String usernameAuditado = username.substring(0, Math.min(username.length(), 60));
        var opt = usuarios.findByUsername(username);
        if (opt.isEmpty() || opt.get().eliminadoEn() != null) {
            encoder.matches(password, hashSenuelo);
            audit.registrarComo(null, usernameAuditado, null, "LOGIN_FALLIDO", "usuarios", null,
                    Map.of("motivo", "usuario_inexistente"), "DENEGADO");
            throw credencialesInvalidas();
        }
        Usuario u = opt.get();
        int minutosBloqueo = config.getInt("seguridad.bloqueo_minutos", 15);
        if (u.bloqueada()) {
            audit.registrarComo(u.id(), u.username(), u.rol().name(), "LOGIN_BLOQUEADO", "usuarios", u.id().toString(),
                    Map.of("bloqueadoHasta", u.bloqueadoHasta().toString()), "DENEGADO");
            throw ApiException.locked("CUENTA_BLOQUEADA", "Cuenta bloqueada temporalmente por intentos fallidos. "
                    + "Intente más tarde o pida a la administradora que la desbloquee.");
        }
        if (!u.activo()) {
            encoder.matches(password, hashSenuelo);
            audit.registrarComo(u.id(), u.username(), u.rol().name(), "LOGIN_FALLIDO", "usuarios", u.id().toString(),
                    Map.of("motivo", "cuenta_inactiva"), "DENEGADO");
            throw credencialesInvalidas();
        }
        if (!encoder.matches(password, u.passwordHash())) {
            boolean bloqueada = usuarios.registrarFalloLogin(u.id(), config.getInt("seguridad.max_intentos", 5), minutosBloqueo);
            audit.registrarComo(u.id(), u.username(), u.rol().name(), bloqueada ? "CUENTA_BLOQUEADA" : "LOGIN_FALLIDO",
                    "usuarios", u.id().toString(), Map.of("motivo", "password_incorrecta"), "DENEGADO");
            if (bloqueada) {
                throw ApiException.locked("CUENTA_BLOQUEADA", "Demasiados intentos fallidos: la cuenta fue bloqueada "
                        + minutosBloqueo + " minutos.");
            }
            throw credencialesInvalidas();
        }
        usuarios.loginExitoso(u.id());
        Emitido e = emitir(u, UUID.randomUUID(), userAgent);
        audit.registrarComo(u.id(), u.username(), u.rol().name(), "LOGIN", "usuarios", u.id().toString(), Map.of(), "OK");
        return e.respuesta();
    }

    @Transactional(noRollbackFor = ApiException.class)
    public TokenResponse refrescar(String refreshCrudo, String userAgent) {
        var rt = refresh.findByHash(TokenHasher.sha256Hex(refreshCrudo))
                .orElseThrow(() -> ApiException.unauthorized("REFRESH_INVALIDO", "Sesión inválida; inicie sesión nuevamente"));
        if (rt.revocadoEn() != null) {
            // Reutilización de un refresh token ya rotado: posible robo; se revoca toda la familia de sesiones.
            refresh.revocarFamilia(rt.familiaId());
            audit.registrarComo(rt.usuarioId(), null, null, "REUSO_REFRESH_TOKEN", "usuarios", rt.usuarioId().toString(),
                    Map.of("familia", rt.familiaId().toString()), "DENEGADO");
            throw ApiException.unauthorized("REFRESH_INVALIDO", "Sesión inválida; inicie sesión nuevamente");
        }
        if (rt.expiraEn().isBefore(OffsetDateTime.now())) {
            throw ApiException.unauthorized("REFRESH_EXPIRADO", "La sesión expiró; inicie sesión nuevamente");
        }
        Usuario u = usuarios.findById(rt.usuarioId()).filter(x -> x.activo() && x.eliminadoEn() == null)
                .orElseThrow(() -> ApiException.unauthorized("REFRESH_INVALIDO", "Sesión inválida; inicie sesión nuevamente"));
        Emitido nuevo = emitir(u, rt.familiaId(), userAgent);
        refresh.revocarYReemplazar(rt.id(), nuevo.refreshId());
        return nuevo.respuesta();
    }

    @Transactional
    public void cerrarSesion(String refreshCrudo) {
        refresh.findByHash(TokenHasher.sha256Hex(refreshCrudo)).ifPresent(rt -> {
            refresh.revocarFamilia(rt.familiaId());
            audit.registrarComo(rt.usuarioId(), null, null, "LOGOUT", "usuarios", rt.usuarioId().toString(), Map.of(), "OK");
        });
    }

    @Transactional
    public TokenResponse cambiarPassword(ChangePasswordRequest r, String userAgent) {
        AuthUser actor = CurrentUser.require();
        Usuario u = usuarios.findByIdForUpdate(actor.id())
                .orElseThrow(() -> ApiException.unauthorized("NO_AUTENTICADO", "Debe iniciar sesión"));
        if (!encoder.matches(r.passwordActual(), u.passwordHash())) {
            audit.denegado(actor, "CAMBIO_PASSWORD_FALLIDO", "usuarios", u.id().toString(), Map.of("motivo", "password_actual_incorrecta"));
            throw ApiException.badRequest("PASSWORD_ACTUAL_INCORRECTA", "La contraseña actual no es correcta");
        }
        policy.validar(r.passwordNueva(), u.username());
        if (encoder.matches(r.passwordNueva(), u.passwordHash())) {
            throw ApiException.badRequest("PASSWORD_REPETIDA", "La nueva contraseña debe ser distinta de la actual");
        }
        usuarios.actualizarPassword(u.id(), encoder.encode(r.passwordNueva()), false);
        refresh.revocarTodosDelUsuario(u.id());
        audit.registrar("CAMBIAR_PASSWORD", "usuarios", u.id().toString(), Map.of());
        Usuario actualizado = usuarios.findById(u.id()).orElseThrow();
        return emitir(actualizado, UUID.randomUUID(), userAgent).respuesta();
    }

    @Transactional(readOnly = true)
    public MeResponse me() {
        AuthUser actor = CurrentUser.require();
        Usuario u = usuarios.findById(actor.id())
                .orElseThrow(() -> ApiException.unauthorized("NO_AUTENTICADO", "Debe iniciar sesión"));
        return new MeResponse(resumen(u), u.debeCambiarPassword(), Permisos.de(u.rol()));
    }

    private Emitido emitir(Usuario u, UUID familia, String userAgent) {
        String crudo = TokenHasher.tokenAleatorio();
        UUID refreshId = refresh.insertar(u.id(), familia, TokenHasher.sha256Hex(crudo), props.jwt().refreshTtlDays(),
                ClientInfo.ip(), userAgent);
        String access = jwt.emitir(u.id(), u.username(), u.rol(), u.tokenVersion());
        return new Emitido(new TokenResponse(access, crudo, "Bearer", jwt.ttlSegundos(), u.debeCambiarPassword(),
                resumen(u), Permisos.de(u.rol())), refreshId);
    }

    private static UsuarioResumen resumen(Usuario u) {
        return new UsuarioResumen(u.id(), u.username(), u.nombreCompleto(), u.rol().name());
    }

    private static ApiException credencialesInvalidas() {
        return ApiException.unauthorized("CREDENCIALES_INVALIDAS", "Usuario o contraseña incorrectos");
    }
}
