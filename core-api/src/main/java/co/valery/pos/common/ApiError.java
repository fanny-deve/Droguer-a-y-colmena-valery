package co.valery.pos.common;

import java.time.OffsetDateTime;
import java.util.List;

public record ApiError(OffsetDateTime timestamp, int status, String code, String message, String path,
                       List<Campo> errores, String traceId) {

    public record Campo(String campo, String mensaje) {}
}
