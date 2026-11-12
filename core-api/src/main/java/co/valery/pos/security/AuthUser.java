package co.valery.pos.security;

import co.valery.pos.common.Rol;
import java.util.UUID;

/** Principal autenticado, reconstruido en cada petición desde la base de datos. */
public record AuthUser(UUID id, String username, Rol rol) {
    public boolean esAdmin() { return rol == Rol.ADMIN; }
}
