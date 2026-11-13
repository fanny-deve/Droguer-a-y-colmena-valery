package co.valery.pos.ventas;

import co.valery.pos.security.TokenHasher;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Sello de integridad de la venta (Ley 527 de 1999): SHA-256 sobre el contenido esencial y la hora del servidor.
 * Cualquier alteración posterior de la venta o de sus ítems hace que el sello recalculado no coincida.
 */
public final class VentaSello {
    private VentaSello() {}

    public record Linea(UUID productoId, UUID loteId, BigDecimal cantidad, BigDecimal precio, BigDecimal subtotal) {}

    public static String calcular(UUID ventaId, UUID idempotencyKey, UUID cajeroId, OffsetDateTime fechaServidor,
                                  String formaPago, BigDecimal total, List<Linea> lineas) {
        StringBuilder sb = new StringBuilder();
        sb.append(ventaId).append('|').append(idempotencyKey).append('|').append(cajeroId).append('|')
                .append(fechaServidor.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)).append('|')
                .append(formaPago).append('|').append(monto(total));
        for (Linea l : lineas) {
            sb.append('|').append(l.productoId()).append(':').append(l.loteId()).append(':')
                    .append(l.cantidad().setScale(3, RoundingMode.HALF_UP).toPlainString()).append(':')
                    .append(monto(l.precio())).append(':').append(monto(l.subtotal()));
        }
        return TokenHasher.sha256Hex(sb.toString());
    }

    private static String monto(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
