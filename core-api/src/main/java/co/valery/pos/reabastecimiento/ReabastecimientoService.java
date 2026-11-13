package co.valery.pos.reabastecimiento;

import co.valery.pos.config.ConfiguracionService;
import co.valery.pos.inventario.AlertaService;
import co.valery.pos.reabastecimiento.PedidoDtos.Sugerencia;
import co.valery.pos.reabastecimiento.PedidoDtos.SugerenciasResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sugerencia de referencias y cantidades a reabastecer (RF-16). Usa existencias, mínimos, pedidos en tránsito, tiempo de
 * entrega del proveedor y la demanda esperada: proyección vigente si existe, o el promedio de las últimas 8 semanas.
 * Es solo una propuesta: la administradora la edita y crea el pedido; el sistema no ejecuta ninguna compra.
 */
@Service
public class ReabastecimientoService {
    private static final Set<String> ENTERAS = Set.of("UNIDAD", "CAJA", "BLISTER", "FRASCO");
    private static final int SEMANAS_HISTORICO = 8;

    private record Candidato(UUID id, String codigo, String nombre, String unidad, BigDecimal costo, BigDecimal minimo, BigDecimal reorden,
                             String abc, UUID proveedorId, String proveedor, int leadDias, BigDecimal vendible, BigDecimal transito) {}

    private final JdbcClient jdbc;
    private final ConfiguracionService config;

    public ReabastecimientoService(JdbcClient jdbc, ConfiguracionService config) {
        this.jdbc = jdbc;
        this.config = config;
    }

    @Transactional(readOnly = true)
    public SugerenciasResponse sugerir(Integer coberturaSemanas, UUID proveedorId) {
        int cobertura = coberturaSemanas != null ? Math.min(Math.max(coberturaSemanas, 1), 26)
                : Math.max(config.getInt("reabastecimiento.cobertura_semanas", 4), 1);
        LocalDate hoy = LocalDate.now(AlertaService.ZONA);
        List<Candidato> candidatos = jdbc.sql("""
                select p.id, p.codigo, p.nombre, p.unidad, p.costo_referencia, p.stock_minimo, p.punto_reorden, p.clasificacion_abc,
                       p.proveedor_principal_id, pr.nombre as proveedor, coalesce(pr.tiempo_entrega_dias, 3) as lead, s.stock_vendible,
                       coalesce((select sum(pi.cantidad_solicitada - pi.cantidad_recibida) from pedido_items pi
                                 join pedidos_proveedor pp on pp.id = pi.pedido_id
                                 where pi.producto_id = p.id and pp.estado in ('ENVIADO', 'RECIBIDO_PARCIAL')
                                   and pi.cantidad_solicitada > pi.cantidad_recibida), 0) as transito
                from productos p join v_stock_productos s on s.producto_id = p.id
                left join proveedores pr on pr.id = p.proveedor_principal_id
                where p.activo and (cast(:prov as uuid) is null or p.proveedor_principal_id = cast(:prov as uuid))
                """).param("prov", proveedorId)
                .query((rs, i) -> new Candidato(rs.getObject("id", UUID.class), rs.getString("codigo"), rs.getString("nombre"),
                        rs.getString("unidad"), rs.getBigDecimal("costo_referencia"), rs.getBigDecimal("stock_minimo"),
                        rs.getBigDecimal("punto_reorden"), rs.getString("clasificacion_abc"), rs.getObject("proveedor_principal_id", UUID.class),
                        rs.getString("proveedor"), rs.getInt("lead"), rs.getBigDecimal("stock_vendible"), rs.getBigDecimal("transito"))).list();

        List<Sugerencia> salida = new ArrayList<>();
        for (Candidato c : candidatos) {
            int leadSemanas = (int) Math.ceil(c.leadDias() / 7.0);
            int horizonte = cobertura + leadSemanas;
            BigDecimal demandaSemanal = BigDecimal.ZERO;
            String fuente = "SIN_DATOS";

            List<BigDecimal> proy = jdbc.sql("""
                    select demanda_proyectada from proyecciones_demanda
                    where producto_id = :p and periodo_fin >= :hoy
                      and corrida_id = (select corrida_id from proyecciones_demanda where producto_id = :p order by generado_en desc limit 1)
                    order by periodo_inicio limit :n
                    """).param("p", c.id()).param("hoy", hoy).param("n", horizonte).query(BigDecimal.class).list();
            if (!proy.isEmpty()) {
                demandaSemanal = proy.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(proy.size()), 3, RoundingMode.HALF_UP);
                fuente = "PROYECCION";
            } else {
                BigDecimal suma = jdbc.sql("select coalesce(sum(unidades), 0) from v_demanda_diaria where producto_id = :p and fecha > :d and fecha <= :h")
                        .param("p", c.id()).param("d", hoy.minusDays(7L * SEMANAS_HISTORICO)).param("h", hoy).query(BigDecimal.class).single();
                if (suma.signum() > 0) {
                    demandaSemanal = suma.divide(BigDecimal.valueOf(SEMANAS_HISTORICO), 3, RoundingMode.HALF_UP);
                    fuente = "HISTORICO";
                }
            }

            List<String> motivos = new ArrayList<>();
            if (c.minimo().signum() > 0 && c.vendible().compareTo(c.minimo()) <= 0) motivos.add("Alcanzó el stock mínimo");
            else if (c.reorden().signum() > 0 && c.vendible().compareTo(c.reorden()) <= 0) motivos.add("Alcanzó el punto de reorden");
            BigDecimal disponible = c.vendible().add(c.transito());
            BigDecimal demandaEntrega = demandaSemanal.multiply(BigDecimal.valueOf(leadSemanas));
            if (demandaSemanal.signum() > 0 && demandaEntrega.compareTo(disponible) > 0) {
                motivos.add("La demanda estimada durante el tiempo de entrega supera las existencias");
            }
            if (motivos.isEmpty()) continue;

            BigDecimal nivelSeguridad = c.minimo().max(c.reorden());
            BigDecimal objetivo = demandaSemanal.multiply(BigDecimal.valueOf(horizonte)).add(nivelSeguridad);
            BigDecimal faltante = objetivo.subtract(disponible);
            if (faltante.signum() <= 0) continue;
            BigDecimal cantidad = ENTERAS.contains(c.unidad()) ? faltante.setScale(0, RoundingMode.CEILING) : faltante.setScale(3, RoundingMode.CEILING);
            salida.add(new Sugerencia(c.id(), c.codigo(), c.nombre(), c.unidad(), c.proveedorId(), c.proveedor(), c.abc(), c.vendible(),
                    c.transito(), demandaSemanal, fuente, cantidad, c.costo(), motivos));
        }
        salida.sort(Comparator.comparing((Sugerencia s) -> s.clasificacionAbc() == null ? "Z" : s.clasificacionAbc())
                .thenComparing(Sugerencia::nombre));
        return new SugerenciasResponse(cobertura, salida,
                "Propuesta editable. El sistema no realiza compras: cree un pedido con las cantidades que decida.");
    }
}
