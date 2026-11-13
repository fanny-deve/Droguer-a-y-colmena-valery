package co.valery.pos.reportes;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/reportes")
@PreAuthorize("hasRole('ADMIN')")
public class ReporteController {
    private final ReporteService reportes;

    public ReporteController(ReporteService reportes) {
        this.reportes = reportes;
    }

    @GetMapping("/ventas/resumen")
    public ReporteService.ResumenVentas resumen(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        return reportes.resumenVentas(desde, hasta);
    }

    @GetMapping("/ventas/por-periodo")
    public List<ReporteService.PuntoSerie> porPeriodo(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                                      @RequestParam(defaultValue = "DIA") String agrupacion) {
        return reportes.ventasPorPeriodo(desde, hasta, agrupacion);
    }

    @GetMapping("/ventas/por-categoria")
    public List<ReporteService.FilaCategoria> porCategoria(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        return reportes.ventasPorCategoria(desde, hasta);
    }

    @GetMapping("/ventas/por-forma-pago")
    public List<ReporteService.FilaFormaPago> porFormaPago(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        return reportes.ventasPorFormaPago(desde, hasta);
    }

    @GetMapping("/ventas/por-producto")
    public List<ReporteService.FilaProducto> porProducto(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                                         @RequestParam(required = false) Integer categoriaId,
                                                         @RequestParam(defaultValue = "20") int limite) {
        return reportes.ventasPorProducto(desde, hasta, categoriaId, limite);
    }

    @GetMapping("/inventario/valorizado")
    public ReporteService.InventarioValorizado valorizado(@RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "50") int size) {
        return reportes.inventarioValorizado(page, size);
    }

    @GetMapping("/inventario/movimientos")
    public List<ReporteService.FilaMovimiento> movimientos(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        return reportes.movimientos(desde, hasta);
    }

    @GetMapping("/inventario/perdidas")
    public ReporteService.ReportePerdidas perdidas(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        return reportes.perdidas(desde, hasta);
    }
}
