package co.valery.pos.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Asigna un identificador a cada petición (cabecera X-Request-Id o uno nuevo), lo pone en los logs y en la respuesta, y registra
 * una línea de acceso (método, ruta SIN parámetros, estado y duración). Nunca registra cuerpos, tokens ni parámetros de consulta.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    public static final String CLAVE = "requestId";
    private static final Pattern VALIDO = Pattern.compile("^[A-Za-z0-9-]{8,64}$");
    private static final Logger acceso = LoggerFactory.getLogger("acceso");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String entrante = req.getHeader("X-Request-Id");
        String id = entrante != null && VALIDO.matcher(entrante).matches() ? entrante : UUID.randomUUID().toString().substring(0, 12);
        MDC.put(CLAVE, id);
        res.setHeader("X-Request-Id", id);
        long inicio = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            if (!req.getRequestURI().startsWith("/actuator/health")) {
                acceso.info("{} {} -> {} ({} ms)", req.getMethod(), req.getRequestURI(), res.getStatus(), (System.nanoTime() - inicio) / 1_000_000);
            }
            MDC.remove(CLAVE);
        }
    }
}
