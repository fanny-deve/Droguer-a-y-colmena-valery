package co.valery.pos.security;

import co.valery.pos.common.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/** Escribe errores JSON desde los filtros de seguridad (fuera de los controladores). */
public final class ErrorWriter {
    private ErrorWriter() {}

    public static void escribir(HttpServletResponse res, ObjectMapper om, HttpStatus st, String code, String msg, String path)
            throws IOException {
        res.setStatus(st.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        om.writeValue(res.getOutputStream(), new ApiError(OffsetDateTime.now(), st.value(), code, msg, path, null,
                UUID.randomUUID().toString().substring(0, 8)));
    }
}
