package co.valery.pos.security;

import co.valery.pos.common.ApiException;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentUser {
    private CurrentUser() {}

    public static Optional<AuthUser> opt() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof AuthUser u) {
            return Optional.of(u);
        }
        return Optional.empty();
    }

    public static AuthUser require() {
        return opt().orElseThrow(() -> ApiException.unauthorized("NO_AUTENTICADO", "Debe iniciar sesión"));
    }
}
