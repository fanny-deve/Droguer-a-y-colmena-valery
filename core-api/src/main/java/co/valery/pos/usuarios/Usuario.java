package co.valery.pos.usuarios;

import co.valery.pos.common.Rol;
import java.time.OffsetDateTime;
import java.util.UUID;

public record Usuario(UUID id, String username, String nombreCompleto, String email, String telefono, Rol rol,
                      boolean activo, boolean debeCambiarPassword, int intentosFallidos, OffsetDateTime bloqueadoHasta,
                      int tokenVersion, OffsetDateTime ultimoLogin, OffsetDateTime creadoEn,
                      OffsetDateTime desactivadoEn, String motivoDesactivacion, OffsetDateTime eliminadoEn,
                      String passwordHash) {

    public boolean bloqueada() {
        return bloqueadoHasta != null && bloqueadoHasta.isAfter(OffsetDateTime.now());
    }

    public boolean sesionValida(int tokenVersionDelJwt) {
        return activo && eliminadoEn == null && tokenVersion == tokenVersionDelJwt;
    }

    public String estado() {
        if (eliminadoEn != null) return "ELIMINADO";
        if (!activo) return "INACTIVO";
        if (bloqueada()) return "BLOQUEADO";
        return "ACTIVO";
    }
}
