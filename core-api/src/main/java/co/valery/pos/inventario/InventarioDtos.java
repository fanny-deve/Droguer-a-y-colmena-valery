package co.valery.pos.inventario;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class InventarioDtos {
    private InventarioDtos() {}

    public record EntradaLoteRequest(
            @NotNull UUID productoId,
            UUID proveedorId,
            @NotBlank @Size(max = 60) String codigoLote,
            LocalDate fechaVencimiento,
            @NotNull @DecimalMin("0.001") @Digits(integer = 9, fraction = 3) BigDecimal cantidad,
            @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal costoUnitario,
            @Size(max = 300) String notas) {}

    public record LoteDto(UUID id, UUID productoId, String productoCodigo, String productoNombre, UUID proveedorId,
                          String codigoLote, LocalDate fechaVencimiento, Long diasParaVencer, BigDecimal cantidadInicial,
                          BigDecimal cantidadDisponible, BigDecimal costoUnitario, BigDecimal valorInventario,
                          String estado, String vigencia, OffsetDateTime fechaIngreso, String notas) {}

    public record AjusteRequest(
            @NotNull UUID loteId,
            @NotBlank String tipo,
            @NotBlank String sentido,
            @NotNull @DecimalMin("0.001") @Digits(integer = 9, fraction = 3) BigDecimal cantidad,
            @NotBlank @Size(min = 5, max = 300) String motivo) {}

    public record AjusteDto(UUID id, UUID loteId, String codigoLote, UUID productoId, String productoNombre, String tipo,
                            String sentido, BigDecimal cantidad, String motivo, BigDecimal costoUnitario,
                            BigDecimal valorTotal, UUID usuarioId, String usuario, OffsetDateTime creadoEn) {}

    public record BloqueoRequest(@NotBlank @Size(min = 5, max = 300) String motivo) {}

    public record MovimientoDto(long id, UUID loteId, String codigoLote, UUID productoId, String productoNombre, String tipo,
                                BigDecimal cantidad, BigDecimal stockLoteAntes, BigDecimal stockLoteDespues,
                                BigDecimal costoUnitario, String referenciaTipo, String referenciaId, String motivo,
                                UUID usuarioId, OffsetDateTime creadoEn) {}

    public record AlertaDto(UUID id, String tipo, String severidad, String mensaje, String estado, UUID productoId,
                            String productoNombre, UUID loteId, String codigoLote, LocalDate fechaVencimiento,
                            Long diasParaVencer, OffsetDateTime generadaEn, OffsetDateTime vistaEn) {}

    public record ResultadoEvaluacion(int creadas, int resueltas) {}
}
