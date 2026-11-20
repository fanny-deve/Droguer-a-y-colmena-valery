package co.valery.pos.reportes;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Reportes de la administradora. Todos aceptan `formato=csv` para descargar el resultado en un archivo abrible con Excel. */
@RestController
@RequestMapping("/api/v1/admin/reportes")
@PreAuthorize("hasRole('ADMIN')")
public class ReporteController {
    private final ReporteService reportes;
    private final ObjectMapper om;

    public ReporteController(ReporteService reportes, ObjectMapper om) {
        this.reportes = reportes;
        this.om = om;
    }

    private ResponseEntity<?> salida(String formato, String nombre, LocalDate desde, LocalDate hasta, Object datos) {
        if (CsvExport.pide(formato)) {
            ReporteService.Rango r = ReporteService.rango(desde, hasta);
            return CsvExport.respuesta("reporte_" + nombre + "_" + r.desde() + "_" + r.hasta(), om, datos);
        }
        return ResponseEntity.ok(datos);
    }

    @GetMapping("/ventas/resumen")
    public ResponseEntity<?> resumen(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                     @RequestParam(defaultValue = "json") String formato) {
        return salida(formato, "ventas_resumen", desde, hasta, reportes.resumenVentas(desde, hasta));
    }

    @GetMapping("/ventas/por-periodo")
    public ResponseEntity<?> porPeriodo(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                        @RequestParam(defaultValue = "DIA") String agrupacion,
                                        @RequestParam(defaultValue = "json") String formato) {
        return salida(formato, "ventas_por_periodo", desde, hasta, reportes.ventasPorPeriodo(desde, hasta, agrupacion));
    }

    @GetMapping("/ventas/por-categoria")
    public ResponseEntity<?> porCategoria(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                          @RequestParam(defaultValue = "json") String formato) {
        return salida(formato, "ventas_por_categoria", desde, hasta, reportes.ventasPorCategoria(desde, hasta));
    }

    @GetMapping("/ventas/por-forma-pago")
    public ResponseEntity<?> porFormaPago(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                          @RequestParam(defaultValue = "json") String formato) {
        return salida(formato, "ventas_por_forma_pago", desde, hasta, reportes.ventasPorFormaPago(desde, hasta));
    }

    @GetMapping("/ventas/por-producto")
    public ResponseEntity<?> porProducto(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                         @RequestParam(required = false) Integer categoriaId,
                                         @RequestParam(defaultValue = "20") int limite,
                                         @RequestParam(defaultValue = "json") String formato) {
        return salida(formato, "ventas_por_producto", desde, hasta, reportes.ventasPorProducto(desde, hasta, categoriaId, limite));
    }

    @GetMapping("/inventario/valorizado")
    public ResponseEntity<?> valorizado(@RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "50") int size,
                                        @RequestParam(defaultValue = "json") String formato) {
        ReporteService.InventarioValorizado v = reportes.inventarioValorizado(page, formato.equalsIgnoreCase("csv") ? 200 : size);
        if (CsvExport.pide(formato)) {
            return CsvExport.respuesta("reporte_inventario_valorizado_" + LocalDate.now(co.valery.pos.inventario.AlertaService.ZONA), om, v.productos().items());
        }
        return ResponseEntity.ok(v);
    }

    @GetMapping("/inventario/movimientos")
    public ResponseEntity<?> movimientos(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                         @RequestParam(defaultValue = "json") String formato) {
        return salida(formato, "inventario_movimientos", desde, hasta, reportes.movimientos(desde, hasta));
    }

    @GetMapping("/inventario/perdidas")
    public ResponseEntity<?> perdidas(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                      @RequestParam(defaultValue = "json") String formato) {
        ReporteService.ReportePerdidas p = reportes.perdidas(desde, hasta);
        return salida(formato, "inventario_perdidas", desde, hasta, CsvExport.pide(formato) ? p.porTipo() : p);
    }
}
