package co.valery.pos.security;

import co.valery.pos.usuarios.Usuario;
import co.valery.pos.usuarios.UsuarioRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Autentica con JWT y revalida la cuenta en base de datos en cada petición: si la administradora desactiva,
 * elimina o resetea a una cajera, su token deja de servir de inmediato (RNF-07).
 */
public class JwtAuthFilter extends OncePerRequestFilter {
    private static final Set<String> RUTAS_CON_PASSWORD_TEMPORAL =
            Set.of("/api/v1/auth/change-password", "/api/v1/auth/me", "/api/v1/auth/logout");

    private final JwtService jwt;
    private final UsuarioRepository usuarios;
    private final ObjectMapper om;

    public JwtAuthFilter(JwtService jwt, UsuarioRepository usuarios, ObjectMapper om) {
        this.jwt = jwt;
        this.usuarios = usuarios;
        this.om = om;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            Optional<Claims> claims = jwt.validar(header.substring(7).trim());
            UUID id = null;
            Integer tv = null;
            if (claims.isPresent()) {
                try {
                    id = UUID.fromString(claims.get().getSubject());
                    tv = claims.get().get("tv", Integer.class);
                } catch (RuntimeException e) {
                    id = null;
                }
            }
            if (id != null && tv != null) {
                Optional<Usuario> u = usuarios.findById(id);
                if (u.isPresent() && u.get().sesionValida(tv)) {
                    if (u.get().debeCambiarPassword() && !RUTAS_CON_PASSWORD_TEMPORAL.contains(req.getRequestURI())) {
                        ErrorWriter.escribir(res, om, HttpStatus.FORBIDDEN, "CAMBIO_PASSWORD_REQUERIDO",
                                "Debe cambiar su contraseña temporal antes de continuar", req.getRequestURI());
                        return;
                    }
                    AuthUser principal = new AuthUser(u.get().id(), u.get().username(), u.get().rol());
                    var auth = new UsernamePasswordAuthenticationToken(principal, null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + principal.rol().name())));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }
        }
        chain.doFilter(req, res);
    }
}
