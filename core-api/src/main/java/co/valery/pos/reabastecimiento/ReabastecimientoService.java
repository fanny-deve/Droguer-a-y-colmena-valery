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
import java.util.HashMap;
import java.util.Map;
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

        // Consultas EN BLOQUE (no una por producto): proyección vigente de cada producto y demanda histórica reciente
        Map<UUID, List<BigDecimal>> proyecciones = new HashMap<>();
        jdbc.sql("""
                select x.producto_id, x.demanda_proyectada
                from proyecciones_demanda x
                join (select distinct on (producto_id) producto_id, corrida_id from proyecciones_demanda
                      order by producto_id, generado_en desc) u on u.producto_id = x.producto_id and u.corrida_id = x.corrida_id
                where x.periodo_fin >= :hoy order by x.producto_id, x.periodo_inicio
                """).param("hoy", hoy).query((rs, i) -> {
                    proyecciones.computeIfAbsent(rs.getObject("producto_id", UUID.class), k -> new ArrayList<>()).add(rs.getBigDecimal("demanda_proyectada"));
                    return 0;
                }).list();
        Map<UUID, BigDecimal> historico = new HashMap<>();
        jdbc.sql("select producto_id, sum(unidades) as total from v_demanda_diaria where fecha > :d and fecha <= :h group by producto_id")
                .param("d", hoy.minusDays(7L * SEMANAS_HISTORICO)).param("h", hoy).query((rs, i) -> {
                    historico.put(rs.getObject("producto_id", UUID.class), rs.getBigDecimal("total"));
                    return 0;
                }).list();

        List<Sugerencia> salida = new ArrayList<>();
        for (Candidato c : candidatos) {
            int leadSemanas = (int) Math.ceil(c.leadDias() / 7.0);
            int horizonte = cobertura + leadSemanas;
            BigDecimal demandaSemanal = BigDecimal.ZERO;
            String fuente = "SIN_DATOS";

            List<BigDecimal> proy = proyecciones.getOrDefault(c.id(), List.of());
            if (!proy.isEmpty()) {
                List<BigDecimal> uso = proy.subList(0, Math.min(proy.size(), horizonte));
                demandaSemanal = uso.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(uso.size()), 3, RoundingMode.HALF_UP);
                fuente = "PROYECCION";
            } else {
                BigDecimal suma = historico.getOrDefault(c.id(), BigDecimal.ZERO);
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
