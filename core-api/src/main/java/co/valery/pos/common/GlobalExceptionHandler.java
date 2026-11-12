package co.valery.pos.common;

import co.valery.pos.auditoria.AuditService;
import co.valery.pos.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final AuditService audit;

    public GlobalExceptionHandler(AuditService audit) {
        this.audit = audit;
    }

    private ResponseEntity<ApiError> build(HttpStatus st, String code, String msg, HttpServletRequest req, List<ApiError.Campo> campos) {
        String trace = UUID.randomUUID().toString().substring(0, 8);
        return ResponseEntity.status(st).body(new ApiError(OffsetDateTime.now(), st.value(), code, msg, req.getRequestURI(), campos, trace));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> api(ApiException e, HttpServletRequest req) {
        return build(e.status(), e.code(), e.getMessage(), req, null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validacion(MethodArgumentNotValidException e, HttpServletRequest req) {
        List<ApiError.Campo> campos = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiError.Campo(f.getField(), f.getDefaultMessage())).toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDACION", "Hay datos inválidos en la solicitud", req, campos);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> restricciones(ConstraintViolationException e, HttpServletRequest req) {
        List<ApiError.Campo> campos = e.getConstraintViolations().stream()
                .map(v -> new ApiError.Campo(v.getPropertyPath().toString(), v.getMessage())).toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDACION", "Hay datos inválidos en la solicitud", req, campos);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> malaSolicitud(Exception e, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "SOLICITUD_INVALIDA", "La solicitud tiene un formato inválido o le faltan datos", req, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> metodo(HttpRequestMethodNotSupportedException e, HttpServletRequest req) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "METODO_NO_PERMITIDO", "Método HTTP no permitido para este recurso", req, null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> noEncontrado(NoResourceFoundException e, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, "NO_ENCONTRADO", "Recurso no encontrado", req, null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> archivoGrande(MaxUploadSizeExceededException e, HttpServletRequest req) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "ARCHIVO_MUY_GRANDE", "El archivo supera el tamaño máximo permitido (5 MB)", req, null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> denegado(AccessDeniedException e, HttpServletRequest req) {
        var u = CurrentUser.opt();
        audit.denegado(u.orElse(null), "ACCESO_DENEGADO", "endpoint", req.getMethod() + " " + req.getRequestURI(), Map.of());
        return build(HttpStatus.FORBIDDEN, "ACCESO_DENEGADO", "No tiene permisos para realizar esta operación", req, null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> integridad(DataIntegrityViolationException e, HttpServletRequest req) {
        String sqlState = null;
        Throwable t = e;
        while (t != null) {
            if (t instanceof SQLException se) { sqlState = se.getSQLState(); break; }
            t = t.getCause();
        }
        log.warn("Violación de integridad (sqlState={}): {}", sqlState, e.getMostSpecificCause().getMessage());
        if ("23505".equals(sqlState)) return build(HttpStatus.CONFLICT, "DUPLICADO", "Ya existe un registro con esos datos únicos", req, null);
        if ("23503".equals(sqlState)) return build(HttpStatus.CONFLICT, "REFERENCIA_INVALIDA", "El registro referencia datos inexistentes o está en uso", req, null);
        if ("23514".equals(sqlState)) return build(HttpStatus.UNPROCESSABLE_ENTITY, "REGLA_DE_DATOS", "Los datos no cumplen las reglas de integridad del sistema", req, null);
        if ("23001".equals(sqlState)) return build(HttpStatus.CONFLICT, "OPERACION_NO_PERMITIDA", "La operación no está permitida por las reglas de integridad", req, null);
        return build(HttpStatus.BAD_REQUEST, "DATOS_INVALIDOS", "Los datos enviados no pudieron guardarse", req, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> inesperado(Exception e, HttpServletRequest req) {
        String trace = UUID.randomUUID().toString().substring(0, 8);
        log.error("Error inesperado [{}] en {} {}", trace, req.getMethod(), req.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError(OffsetDateTime.now(), 500,
                "ERROR_INTERNO", "Ocurrió un error interno. Referencia: " + trace, req.getRequestURI(), null, trace));
    }
}
