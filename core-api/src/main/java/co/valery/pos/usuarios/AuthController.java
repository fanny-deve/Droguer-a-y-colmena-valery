package co.valery.pos.usuarios;

import co.valery.pos.usuarios.UsuarioDtos.ChangePasswordRequest;
import co.valery.pos.usuarios.UsuarioDtos.LoginRequest;
import co.valery.pos.usuarios.UsuarioDtos.MeResponse;
import co.valery.pos.usuarios.UsuarioDtos.RefreshRequest;
import co.valery.pos.usuarios.UsuarioDtos.TokenResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest r,
                               @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return auth.login(r.username(), r.password(), userAgent);
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest r,
                                 @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return auth.refrescar(r.refreshToken(), userAgent);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest r) {
        auth.cerrarSesion(r.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/change-password")
    public TokenResponse cambiarPassword(@Valid @RequestBody ChangePasswordRequest r,
                                         @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return auth.cambiarPassword(r, userAgent);
    }

    @GetMapping("/me")
    public MeResponse me() {
        return auth.me();
    }
}
