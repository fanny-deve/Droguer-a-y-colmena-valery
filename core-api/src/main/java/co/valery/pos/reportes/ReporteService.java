package co.valery.pos.reportes;

import co.valery.pos.common.ApiException;
import co.valery.pos.common.PageResponse;
import co.valery.pos.inventario.AlertaService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reportes descriptivos (RF-17): ventas por periodo, categoría, forma de pago y producto; movimientos de inventario;
 * pérdidas por vencimiento. Solo ventas CONFIRMADAS cuentan como ingreso; filtran por rango de fechas (zona Colombia).
 * Contienen costos y márgenes, por lo que son exclusivos de la administradora.
 */
@Service
public class ReporteService {
    public record Rango(LocalDate desde, LocalDate hasta) {}

    public record ResumenVentas(Rango rango, long ventas, BigDecimal subtotal, BigDecimal descuentos, BigDecimal total,
                                BigDecimal costo, BigDecimal margenBruto, BigDecimal margenPorcentaje, BigDecimal ticketPromedio,
                                long anuladas, BigDecimal totalAnulado) {}

    public record PuntoSerie(LocalDate periodo, long ventas, BigDecimal total) {}

    public record FilaCategoria(int categoriaId, String categoria, BigDecimal unidades, BigDecimal ingresos, BigDecimal costo, BigDecimal margen) {}

    public record FilaFormaPago(String formaPago, long ventas, BigDecimal total, BigDecimal participacion) {}

    public record FilaProducto(UUID productoId, String codigo, String nombre, BigDecimal unidades, BigDecimal ingresos, BigDecimal costo, BigDecimal margen) {}

    public record FilaValorizado(UUID productoId, String codigo, String nombre, String categoria, BigDecimal unidades,
                                 BigDecimal valorVendible, BigDecimal valorVencido, BigDecimal valorBloqueado, BigDecimal valorTotal) {}

    public record InventarioValorizado(BigDecimal valorVendible, BigDecimal valorVencido, BigDecimal valorBloqueado, BigDecimal valorTotal,
                                       PageResponse<FilaValorizado> productos) {}

    public record FilaMovimiento(String tipo, long movimientos, BigDecimal unidades, BigDecimal valor) {}

    public record FilaPerdida(String tipo, BigDecimal unidades, BigDecimal valor) {}

    public record ReportePerdidas(Rango rango, List<FilaPerdida> porTipo, BigDecimal totalPerdido, BigDecimal vencidoPendienteUnidades,
                                  BigDecimal vencidoPendienteValor) {}

    private final JdbcClient jdbc;

    public ReporteService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public static Rango rango(LocalDate desde, LocalDate hasta) {
        LocalDate hoy = LocalDate.now(AlertaService.ZONA);
        LocalDate h = hasta == null ? hoy : hasta;
        LocalDate d = desde == null ? h.minusDays(29) : desde;
        if (d.isAfter(h)) throw ApiException.badRequest("RANGO_INVALIDO", "La fecha inicial no puede ser posterior a la final");
        if (ChronoUnit.DAYS.between(d, h) > 731) throw ApiException.badRequest("RANGO_MUY_AMPLIO", "El rango máximo de un reporte es de 2 años");
        return new Rango(d, h);
    }

    private static final String VENTAS_EN_RANGO = """
            v.estado = 'CONFIRMADA'
              and v.fecha_servidor >= (cast(:d as date)::timestamp at time zone 'America/Bogota')
              and v.fecha_servidor < ((cast(:h as date) + 1)::timestamp at time zone 'America/Bogota')
            """;

    private static BigDecimal pct(BigDecimal parte, BigDecimal todo) {
        return todo.signum() == 0 ? BigDecimal.ZERO : parte.multiply(BigDecimal.valueOf(100)).divide(todo, 2, RoundingMode.HALF_UP);
    }

    @Transactional(readOnly = true)
    public ResumenVentas resumenVentas(LocalDate desde, LocalDate hasta) {
        Rango r = rango(desde, hasta);
        Object[] a = jdbc.sql("""
                select count(*) filter (where estado = 'CONFIRMADA'), coalesce(sum(subtotal) filter (where estado = 'CONFIRMADA'), 0),
                       coalesce(sum(descuento) filter (where estado = 'CONFIRMADA'), 0), coalesce(sum(total) filter (where estado = 'CONFIRMADA'), 0),
                       count(*) filter (where estado = 'ANULADA'), coalesce(sum(total) filter (where estado = 'ANULADA'), 0)
                from ventas v
                where v.fecha_servidor >= (cast(:d as date)::timestamp at time zone 'America/Bogota')
                  and v.fecha_servidor < ((cast(:h as date) + 1)::timestamp at time zone 'America/Bogota')
                """).param("d", r.desde()).param("h", r.hasta())
                .query((rs, i) -> new Object[]{rs.getLong(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getLong(5),
                        rs.getBigDecimal(6)}).single();
        BigDecimal costo = jdbc.sql("select coalesce(sum(vi.cantidad * vi.costo_unitario), 0) from venta_items vi join ventas v on v.id = vi.venta_id where "
                        + VENTAS_EN_RANGO).param("d", r.desde()).param("h", r.hasta()).query(BigDecimal.class).single().setScale(2, RoundingMode.HALF_UP);
        long ventas = (Long) a[0];
        BigDecimal total = (BigDecimal) a[3];
        BigDecimal margen = total.subtract(costo);
        BigDecimal ticket = ventas == 0 ? BigDecimal.ZERO : total.divide(BigDecimal.valueOf(ventas), 2, RoundingMode.HALF_UP);
        return new ResumenVentas(r, ventas, (BigDecimal) a[1], (BigDecimal) a[2], total, costo, margen, pct(margen, total), ticket,
                (Long) a[4], (BigDecimal) a[5]);
    }

    @Transactional(readOnly = true)
    public List<PuntoSerie> ventasPorPeriodo(LocalDate desde, LocalDate hasta, String agrupacion) {
        Rango r = rango(desde, hasta);
        String g = agrupacion == null ? "DIA" : agrupacion.trim().toUpperCase(Locale.ROOT);
        String trunc = switch (g) {
            case "DIA" -> "day";
            case "SEMANA" -> "week";
            case "MES" -> "month";
            default -> throw ApiException.badRequest("AGRUPACION_INVALIDA", "Agrupación válida: DIA, SEMANA o MES");
        };
        return jdbc.sql("""
                select date_trunc(cast(:g as text), v.fecha_servidor at time zone 'America/Bogota')::date as periodo,
                       count(*) as ventas, coalesce(sum(v.total), 0) as total
                from ventas v where """ + VENTAS_EN_RANGO + " group by 1 order by 1")
                .param("g", trunc).param("d", r.desde()).param("h", r.hasta())
                .query((rs, i) -> new PuntoSerie(rs.getObject("periodo", LocalDate.class), rs.getLong("ventas"), rs.getBigDecimal("total"))).list();
    }

    @Transactional(readOnly = true)
    public List<FilaCategoria> ventasPorCategoria(LocalDate desde, LocalDate hasta) {
        Rango r = rango(desde, hasta);
        return jdbc.sql("""
                select c.id, c.nombre, sum(vi.cantidad) as unidades, sum(vi.subtotal) as ingresos, sum(vi.cantidad * vi.costo_unitario) as costo
                from venta_items vi join ventas v on v.id = vi.venta_id join categorias c on c.id = vi.categoria_id
                where """ + VENTAS_EN_RANGO + " group by c.id, c.nombre order by ingresos desc")
                .param("d", r.desde()).param("h", r.hasta())
                .query((rs, i) -> new FilaCategoria(rs.getInt("id"), rs.getString("nombre"), rs.getBigDecimal("unidades"),
                        rs.getBigDecimal("ingresos"), rs.getBigDecimal("costo").setScale(2, RoundingMode.HALF_UP),
                        rs.getBigDecimal("ingresos").subtract(rs.getBigDecimal("costo")).setScale(2, RoundingMode.HALF_UP))).list();
    }

    @Transactional(readOnly = true)
    public List<FilaFormaPago> ventasPorFormaPago(LocalDate desde, LocalDate hasta) {
        Rango r = rango(desde, hasta);
        List<FilaFormaPago> base = jdbc.sql("select v.forma_pago, count(*) as ventas, sum(v.total) as total from ventas v where "
                        + VENTAS_EN_RANGO + " group by v.forma_pago order by total desc")
                .param("d", r.desde()).param("h", r.hasta())
                .query((rs, i) -> new FilaFormaPago(rs.getString("forma_pago"), rs.getLong("ventas"), rs.getBigDecimal("total"), BigDecimal.ZERO)).list();
        BigDecimal todo = base.stream().map(FilaFormaPago::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        return base.stream().map(f -> new FilaFormaPago(f.formaPago(), f.ventas(), f.total(), pct(f.total(), todo))).toList();
    }

    @Transactional(readOnly = true)
    public List<FilaProducto> ventasPorProducto(LocalDate desde, LocalDate hasta, Integer categoriaId, int limite) {
        Rango r = rango(desde, hasta);
        int lim = Math.min(Math.max(limite, 1), 500);
        return jdbc.sql("""
                select p.id, p.codigo, p.nombre, sum(vi.cantidad) as unidades, sum(vi.subtotal) as ingresos,
                       sum(vi.cantidad * vi.costo_unitario) as costo
                from venta_items vi join ventas v on v.id = vi.venta_id join productos p on p.id = vi.producto_id
                where """ + VENTAS_EN_RANGO + """
                 and (cast(:cat as int) is null or vi.categoria_id = cast(:cat as int))
                group by p.id, p.codigo, p.nombre order by ingresos desc limit :lim
                """)
                .param("d", r.desde()).param("h", r.hasta()).param("cat", categoriaId).param("lim", lim)
                .query((rs, i) -> new FilaProducto(rs.getObject("id", UUID.class), rs.getString("codigo"), rs.getString("nombre"),
                        rs.getBigDecimal("unidades"), rs.getBigDecimal("ingresos"), rs.getBigDecimal("costo").setScale(2, RoundingMode.HALF_UP),
                        rs.getBigDecimal("ingresos").subtract(rs.getBigDecimal("costo")).setScale(2, RoundingMode.HALF_UP))).list();
    }

    @Transactional(readOnly = true)
    public InventarioValorizado inventarioValorizado(int page, int size) {
        int tam = PageResponse.limitarTamano(size);
        int pag = Math.max(page, 0);
        String valor = """
                coalesce(sum(l.cantidad_disponible * l.costo_unitario) filter (where %s), 0)
                """;
        String vendible = String.format(valor, "l.estado = 'ACTIVO' and (l.fecha_vencimiento is null or l.fecha_vencimiento > fn_hoy())");
        String vencido = String.format(valor, "l.estado <> 'BLOQUEADO' and l.fecha_vencimiento is not null and l.fecha_vencimiento <= fn_hoy()");
        String bloqueado = String.format(valor, "l.estado = 'BLOQUEADO'");
        Object[] tot = jdbc.sql("select " + vendible + ", " + vencido + ", " + bloqueado + " from lotes l where l.cantidad_disponible > 0")
                .query((rs, i) -> new Object[]{rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3)}).single();
        long total = jdbc.sql("select count(distinct l.producto_id) from lotes l where l.cantidad_disponible > 0").query(Long.class).single();
        List<FilaValorizado> filas = jdbc.sql("select p.id, p.codigo, p.nombre, c.nombre as categoria, sum(l.cantidad_disponible) as unidades, "
                        + vendible + " as vendible, " + vencido + " as vencido, " + bloqueado + " as bloqueado "
                        + "from lotes l join productos p on p.id = l.producto_id join categorias c on c.id = p.categoria_id "
                        + "where l.cantidad_disponible > 0 group by p.id, p.codigo, p.nombre, c.nombre order by p.nombre limit :l offset :o")
                .param("l", tam).param("o", pag * tam)
                .query((rs, i) -> new FilaValorizado(rs.getObject("id", UUID.class), rs.getString("codigo"), rs.getString("nombre"),
                        rs.getString("categoria"), rs.getBigDecimal("unidades"), rs.getBigDecimal("vendible"), rs.getBigDecimal("vencido"),
                        rs.getBigDecimal("bloqueado"),
                        rs.getBigDecimal("vendible").add(rs.getBigDecimal("vencido")).add(rs.getBigDecimal("bloqueado")))).list();
        BigDecimal v1 = (BigDecimal) tot[0], v2 = (BigDecimal) tot[1], v3 = (BigDecimal) tot[2];
        return new InventarioValorizado(v1, v2, v3, v1.add(v2).add(v3), new PageResponse<>(filas, pag, tam, total));
    }

    @Transactional(readOnly = true)
    public List<FilaMovimiento> movimientos(LocalDate desde, LocalDate hasta) {
        Rango r = rango(desde, hasta);
        return jdbc.sql("""
                select m.tipo, count(*) as movimientos, sum(m.cantidad) as unidades, sum(abs(m.cantidad) * m.costo_unitario) as valor
                from movimientos_inventario m
                where m.creado_en >= (cast(:d as date)::timestamp at time zone 'America/Bogota')
                  and m.creado_en < ((cast(:h as date) + 1)::timestamp at time zone 'America/Bogota')
                group by m.tipo order by m.tipo
                """).param("d", r.desde()).param("h", r.hasta())
                .query((rs, i) -> new FilaMovimiento(rs.getString("tipo"), rs.getLong("movimientos"), rs.getBigDecimal("unidades"),
                        rs.getBigDecimal("valor").setScale(2, RoundingMode.HALF_UP))).list();
    }

    @Transactional(readOnly = true)
    public ReportePerdidas perdidas(LocalDate desde, LocalDate hasta) {
        Rango r = rango(desde, hasta);
        List<FilaPerdida> porTipo = jdbc.sql("""
                select a.tipo, sum(a.cantidad) as unidades, sum(a.valor_total) as valor from ajustes_inventario a
                where a.sentido = 'NEGATIVO'
                  and a.creado_en >= (cast(:d as date)::timestamp at time zone 'America/Bogota')
                  and a.creado_en < ((cast(:h as date) + 1)::timestamp at time zone 'America/Bogota')
                group by a.tipo order by valor desc
                """).param("d", r.desde()).param("h", r.hasta())
                .query((rs, i) -> new FilaPerdida(rs.getString("tipo"), rs.getBigDecimal("unidades"), rs.getBigDecimal("valor"))).list();
        BigDecimal total = porTipo.stream().map(FilaPerdida::valor).reduce(BigDecimal.ZERO, BigDecimal::add);
        Object[] pend = jdbc.sql("""
                select coalesce(sum(cantidad_disponible), 0), coalesce(sum(cantidad_disponible * costo_unitario), 0) from lotes
                where cantidad_disponible > 0 and fecha_vencimiento is not null and fecha_vencimiento <= fn_hoy()
                """).query((rs, i) -> new Object[]{rs.getBigDecimal(1), rs.getBigDecimal(2)}).single();
        return new ReportePerdidas(r, porTipo, total, (BigDecimal) pend[0], ((BigDecimal) pend[1]).setScale(2, RoundingMode.HALF_UP));
    }
}
