package co.valery.pos.catalogo;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

public final class CatalogoDtos {
    private CatalogoDtos() {}

    public record CategoriaDto(int id, String nombre, String descripcion, boolean activa) {}

    public record CategoriaRequest(@NotBlank @Size(min = 2, max = 80) String nombre, @Size(max = 200) String descripcion) {}

    public record ProveedorDto(UUID id, String nombre, String nit, String contactoNombre, String telefono, String email,
                               String direccion, int tiempoEntregaDias, String notas, boolean activo) {}

    public record ProveedorRequest(
            @NotBlank @Size(min = 2, max = 120) String nombre,
            @Size(max = 30) String nit,
            @Size(max = 120) String contactoNombre,
            @Pattern(regexp = "^[0-9+() -]{7,20}$", message = "Teléfono inválido") String telefono,
            @Email @Size(max = 150) String email,
            @Size(max = 200) String direccion,
            @NotNull @Min(0) @Max(90) Integer tiempoEntregaDias,
            @Size(max = 500) String notas) {}

    public record ProveedorProductoRequest(@NotNull UUID productoId, @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal costoUltimo) {}

    public record ProveedorProductoDto(UUID productoId, String codigo, String nombre, BigDecimal costoUltimo) {}

    /** Vista de venta: lo que la cajera puede ver. Sin costos, márgenes, proveedor ni clasificación. */
    public record ProductoVentaDto(UUID id, String codigo, String nombre, String descripcion, int categoriaId, String categoria,
                                   String unidad, BigDecimal precioVenta, BigDecimal stockVendible, boolean manejaVencimiento,
                                   boolean activo) {}

    /** Vista de administración: incluye costos, márgenes y parámetros de inventario. */
    public record ProductoAdminDto(UUID id, String codigo, String nombre, String descripcion, int categoriaId, String categoria,
                                   String unidad, BigDecimal precioVenta, BigDecimal costoReferencia, BigDecimal margenPorcentaje,
                                   boolean manejaVencimiento, BigDecimal stockMinimo, BigDecimal puntoReorden,
                                   UUID proveedorPrincipalId, String clasificacionAbc, BigDecimal stockTotal,
                                   BigDecimal stockVendible, BigDecimal stockVencido, boolean activo) {}

    public record ProductoRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9._-]{3,30}$", message = "3 a 30 caracteres: letras, números, punto, guion") String codigo,
            @NotBlank @Size(min = 2, max = 150) String nombre,
            @Size(max = 500) String descripcion,
            @NotNull Integer categoriaId,
            @NotBlank String unidad,
            @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal precioVenta,
            @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal costoReferencia,
            Boolean manejaVencimiento,
            @DecimalMin("0") @Digits(integer = 9, fraction = 3) BigDecimal stockMinimo,
            @DecimalMin("0") @Digits(integer = 9, fraction = 3) BigDecimal puntoReorden,
            UUID proveedorPrincipalId) {}

    /** El código y si maneja vencimiento no cambian después de creado el producto. */
    public record ProductoActualizarRequest(
            @NotBlank @Size(min = 2, max = 150) String nombre,
            @Size(max = 500) String descripcion,
            @NotNull Integer categoriaId,
            @NotBlank String unidad,
            @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal precioVenta,
            @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal costoReferencia,
            @DecimalMin("0") @Digits(integer = 9, fraction = 3) BigDecimal stockMinimo,
            @DecimalMin("0") @Digits(integer = 9, fraction = 3) BigDecimal puntoReorden,
            UUID proveedorPrincipalId) {}
}
