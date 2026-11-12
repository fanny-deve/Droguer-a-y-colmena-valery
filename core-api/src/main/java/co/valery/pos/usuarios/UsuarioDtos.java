package co.valery.pos.usuarios;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class UsuarioDtos {
    private UsuarioDtos() {}

    public record LoginRequest(@NotBlank @Size(max = 100) String username, @NotBlank @Size(max = 200) String password) {}

    public record RefreshRequest(@NotBlank @Size(max = 200) String refreshToken) {}

    public record ChangePasswordRequest(@NotBlank @Size(max = 200) String passwordActual,
                                        @NotBlank @Size(max = 200) String passwordNueva) {}

    public record UsuarioResumen(UUID id, String username, String nombreCompleto, String rol) {}

    public record TokenResponse(String accessToken, String refreshToken, String tokenType, long expiresIn,
                                boolean debeCambiarPassword, UsuarioResumen usuario, List<String> permisos) {}

    public record MeResponse(UsuarioResumen usuario, boolean debeCambiarPassword, List<String> permisos) {}

    public record CrearCajeraRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9._-]{4,40}$", message = "4 a 40 caracteres: letras, números, punto, guion o guion bajo") String username,
            @NotBlank @Size(min = 3, max = 120) String nombreCompleto,
            @Email @Size(max = 150) String email,
            @Pattern(regexp = "^[0-9+() -]{7,20}$", message = "Teléfono inválido") String telefono) {}

    public record ActualizarCajeraRequest(
            @NotBlank @Size(min = 3, max = 120) String nombreCompleto,
            @Email @Size(max = 150) String email,
            @Pattern(regexp = "^[0-9+() -]{7,20}$", message = "Teléfono inválido") String telefono) {}

    public record MotivoRequest(@NotBlank @Size(min = 5, max = 300) String motivo) {}

    public record EliminarRequest(@NotBlank @Size(min = 5, max = 300) String motivo,
                                  @NotBlank @Size(max = 200) String passwordAdmin) {}

    public record UsuarioDto(UUID id, String username, String nombreCompleto, String email, String telefono, String rol,
                             String estado, boolean debeCambiarPassword, OffsetDateTime bloqueadoHasta,
                             OffsetDateTime ultimoLogin, OffsetDateTime creadoEn, OffsetDateTime desactivadoEn,
                             String motivoDesactivacion, OffsetDateTime eliminadoEn) {
        public static UsuarioDto de(Usuario u) {
            return new UsuarioDto(u.id(), u.username(), u.nombreCompleto(), u.email(), u.telefono(), u.rol().name(),
                    u.estado(), u.debeCambiarPassword(), u.bloqueadoHasta(), u.ultimoLogin(), u.creadoEn(),
                    u.desactivadoEn(), u.motivoDesactivacion(), u.eliminadoEn());
        }
    }

    public record CredencialTemporalResponse(UsuarioDto usuario, String passwordTemporal, String aviso) {}
}
