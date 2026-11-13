package co.valery.pos.ventas;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class VentaDtos {
    private VentaDtos() {}

    public record ItemRequest(@NotNull UUID productoId,
                              @NotNull @DecimalMin("0.001") @Digits(integer = 9, fraction = 3) BigDecimal cantidad) {}

    /**
     * Venta del punto de venta. `idempotencyKey` lo genera el cliente (UUID) y garantiza que reintentos o
     * sincronizaciones repetidas nunca dupliquen la venta (RF-22). El precio SIEMPRE lo fija el servidor.
     */
    public record VentaRequest(@NotNull UUID idempotencyKey,
                               @NotEmpty @Size(max = 100) List<@Valid @NotNull ItemRequest> items,
                               @NotBlank String formaPago,
                               @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal montoRecibido,
                               @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal descuento,
                               OffsetDateTime fechaCliente) {}

    public record VentaItemDto(int linea, UUID productoId, String codigo, String nombre, UUID loteId, String codigoLote,
                               BigDecimal cantidad, BigDecimal precioUnitario, BigDecimal subtotal,
                               BigDecimal costoUnitario) {}

    public record VentaDto(UUID id, long numero, String comprobante, UUID cajeroId, String cajero, String formaPago,
                           BigDecimal subtotal, BigDecimal descuento, BigDecimal total, BigDecimal montoRecibido,
                           BigDecimal cambio, String estado, String origen, OffsetDateTime fechaServidor,
                           OffsetDateTime fechaCliente, String hashSellado, boolean duplicada, List<VentaItemDto> items,
                           OffsetDateTime anuladaEn, String motivoAnulacion) {}

    public record VentaResumenDto(UUID id, long numero, String comprobante, UUID cajeroId, String cajero, String formaPago,
                                  BigDecimal total, String estado, String origen, OffsetDateTime fechaServidor, int articulos) {}

    public record ComprobanteItem(String descripcion, BigDecimal cantidad, BigDecimal precioUnitario, BigDecimal subtotal) {}

    public record ComprobanteDto(String negocio, String documento, String comprobante, String fecha, String cajera,
                                 List<ComprobanteItem> items, BigDecimal subtotal, BigDecimal descuento, BigDecimal total,
                                 String formaPago, BigDecimal recibido, BigDecimal cambio, String estado, String sello,
                                 String leyenda) {}

    public record AnularRequest(@NotBlank @Size(min = 5, max = 300) String motivo) {}

    public record VerificacionSello(UUID ventaId, long numero, boolean integro, String selloAlmacenado, String selloCalculado) {}

    // ---------- Caja ----------

    public record AbrirCajaRequest(@NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal fondoInicial) {}

    public record CerrarCajaRequest(@NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal efectivoContado,
                                    @Size(max = 300) String observaciones) {}

    public record CierreForzadoRequest(@NotBlank @Size(min = 5, max = 300) String motivo,
                                       @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal efectivoContado) {}

    public record ResumenCaja(long ventas, long anuladas, BigDecimal totalVentas, Map<String, BigDecimal> porFormaPago,
                              BigDecimal efectivoEsperado) {}

    public record SesionCajaDto(UUID id, UUID cajeroId, String cajero, String estado, OffsetDateTime abiertaEn,
                                BigDecimal fondoInicial, OffsetDateTime cerradaEn, BigDecimal efectivoEsperado,
                                BigDecimal efectivoContado, BigDecimal diferencia, String observaciones, ResumenCaja resumen) {}

    // ---------- Sincronización offline ----------

    public record SyncRequest(@NotEmpty @Size(max = 200) List<@Valid @NotNull VentaRequest> ventas) {}

    public record SyncItemResultado(UUID idempotencyKey, String estado, UUID ventaId, Long numero, String codigo, String motivo) {}

    public record SyncResponse(int confirmadas, int duplicadas, int rechazadas, List<SyncItemResultado> resultados) {}

    public record RechazoSyncDto(UUID id, UUID idempotencyKey, UUID cajeroId, String cajero, String motivo, String estado,
                                 OffsetDateTime fechaCliente, OffsetDateTime recibidoEn, OffsetDateTime resueltaEn,
                                 String notaResolucion, Object payload) {}

    public record ResolverRechazoRequest(@NotBlank @Size(min = 5, max = 300) String nota) {}
}
