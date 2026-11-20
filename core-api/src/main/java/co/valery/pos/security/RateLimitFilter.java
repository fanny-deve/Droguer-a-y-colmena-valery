package co.valery.pos.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/** Frena la fuerza bruta y los abusos: límite estricto en login/refresh y uno amplio para el resto de la API (por IP). */
public class RateLimitFilter extends OncePerRequestFilter {
    private final RateLimiter auth;
    private final RateLimiter api;
    private final ObjectMapper om;

    public RateLimitFilter(int authPorMinuto, int apiPorMinuto, ObjectMapper om) {
        this.auth = new RateLimiter(authPorMinuto);
        this.api = new RateLimiter(apiPorMinuto);
        this.om = om;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return req.getRequestURI().startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String uri = req.getRequestURI();
        boolean esAuth = uri.equals("/api/v1/auth/login") || uri.equals("/api/v1/auth/refresh");
        String ip = req.getRemoteAddr();
        int espera = (esAuth ? auth : api).intentar((esAuth ? "a:" : "g:") + ip, System.currentTimeMillis());
        if (espera > 0) {
            res.setHeader("Retry-After", String.valueOf(espera));
            ErrorWriter.escribir(res, om, HttpStatus.TOO_MANY_REQUESTS, "DEMASIADAS_PETICIONES",
                    "Demasiadas solicitudes desde su conexión. Intente de nuevo en " + espera + " segundos.", uri);
            return;
        }
        chain.doFilter(req, res);
    }
}
