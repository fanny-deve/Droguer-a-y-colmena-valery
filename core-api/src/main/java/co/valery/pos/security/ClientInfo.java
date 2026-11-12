package co.valery.pos.security;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public final class ClientInfo {
    private ClientInfo() {}

    /** IP de origen de la petición actual (respeta X-Forwarded-For configurado en el servidor). */
    public static String ip() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes sra) {
            return sra.getRequest().getRemoteAddr();
        }
        return null;
    }
}
