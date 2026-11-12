package co.valery.pos.inventario;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Único punto de escritura del kardex: cada variación de existencias deja un movimiento inmutable (RNF-10). */
@Service
public class KardexService {
    private final JdbcClient jdbc;

    public KardexService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long registrar(UUID loteId, UUID productoId, String tipo, BigDecimal cantidadConSigno, BigDecimal antes,
                          BigDecimal despues, BigDecimal costoUnitario, String referenciaTipo, String referenciaId,
                          String motivo, UUID usuarioId) {
        return jdbc.sql("""
                insert into movimientos_inventario(lote_id, producto_id, tipo, cantidad, stock_lote_antes, stock_lote_despues,
                       costo_unitario, referencia_tipo, referencia_id, motivo, usuario_id)
                values (:lote, :prod, :tipo, :cant, :antes, :despues, :costo, cast(:rt as text), cast(:ri as text),
                        cast(:motivo as text), cast(:u as uuid)) returning id
                """)
                .param("lote", loteId).param("prod", productoId).param("tipo", tipo).param("cant", cantidadConSigno)
                .param("antes", antes).param("despues", despues).param("costo", costoUnitario)
                .param("rt", referenciaTipo).param("ri", referenciaId).param("motivo", motivo).param("u", usuarioId)
                .query((rs, i) -> rs.getLong(1)).single();
    }
}
