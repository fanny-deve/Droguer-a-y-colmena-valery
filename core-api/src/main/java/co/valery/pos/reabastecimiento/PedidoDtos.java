package co.valery.pos.reabastecimiento;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class PedidoDtos {
    private PedidoDtos() {}

    public record PedidoItemRequest(@NotNull UUID productoId,
                                    @NotNull @DecimalMin("0.001") @Digits(integer = 9, fraction = 3) BigDecimal cantidad,
                                    @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal costoUnitarioEstimado,
                                    Boolean sugerido) {}

    public record PedidoRequest(@NotNull UUID proveedorId, LocalDate fechaEsperada, @Size(max = 500) String notas,
                                @NotEmpty @Size(max = 200) List<@Valid @NotNull PedidoItemRequest> items) {}

    public record PedidoItemDto(UUID productoId, String codigo, String nombre, BigDecimal cantidadSolicitada, BigDecimal cantidadRecibida,
                                BigDecimal pendiente, BigDecimal costoUnitarioEstimado, boolean sugerido) {}

    public record PedidoDto(UUID id, long numero, UUID proveedorId, String proveedor, String estado, LocalDate fechaEsperada, String notas,
                            UUID creadoPor, OffsetDateTime creadoEn, OffsetDateTime enviadoEn, OffsetDateTime cerradoEn,
                            BigDecimal totalEstimado, List<PedidoItemDto> items) {}

    public record PedidoResumenDto(UUID id, long numero, String proveedor, String estado, LocalDate fechaEsperada, OffsetDateTime creadoEn,
                                   int referencias, BigDecimal totalEstimado) {}

    public record RecepcionItem(@NotNull UUID productoId, @NotBlank @Size(max = 60) String codigoLote, LocalDate fechaVencimiento,
                                @NotNull @DecimalMin("0.001") @Digits(integer = 9, fraction = 3) BigDecimal cantidad,
                                @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal costoUnitario,
                                @Size(max = 300) String notas) {}

    public record RecepcionRequest(@NotEmpty @Size(max = 200) List<@Valid @NotNull RecepcionItem> items) {}

    public record MotivoPedidoRequest(@NotBlank @Size(min = 5, max = 300) String motivo) {}

    public record Sugerencia(UUID productoId, String codigo, String nombre, String unidad, UUID proveedorId, String proveedor,
                             String clasificacionAbc, BigDecimal stockVendible, BigDecimal enTransito, BigDecimal demandaSemanal,
                             String fuenteDemanda, BigDecimal cantidadSugerida, BigDecimal costoUnitarioEstimado, List<String> motivos) {}

    public record SugerenciasResponse(int cobertura, List<Sugerencia> sugerencias, String aviso) {}
}
