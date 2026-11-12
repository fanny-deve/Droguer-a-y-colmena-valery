package co.valery.pos.catalogo;

import co.valery.pos.catalogo.CatalogoDtos.CategoriaDto;
import co.valery.pos.catalogo.CatalogoDtos.CategoriaRequest;
import co.valery.pos.catalogo.CatalogoDtos.ProductoActualizarRequest;
import co.valery.pos.catalogo.CatalogoDtos.ProductoAdminDto;
import co.valery.pos.catalogo.CatalogoDtos.ProductoRequest;
import co.valery.pos.catalogo.CatalogoDtos.ProductoVentaDto;
import co.valery.pos.catalogo.CatalogoDtos.ProveedorDto;
import co.valery.pos.catalogo.CatalogoDtos.ProveedorProductoDto;
import co.valery.pos.catalogo.CatalogoDtos.ProveedorProductoRequest;
import co.valery.pos.catalogo.CatalogoDtos.ProveedorRequest;
import co.valery.pos.common.PageResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

public final class CatalogoControllers {
    private CatalogoControllers() {}

    /** Consulta de catálogo para el punto de venta: disponible para cajera y administradora, sin datos de costo. */
    @RestController
    @RequestMapping("/api/v1")
    public static class ConsultaController {
        private final ProductoService productos;
        private final CategoriaService categorias;

        public ConsultaController(ProductoService productos, CategoriaService categorias) {
            this.productos = productos;
            this.categorias = categorias;
        }

        @GetMapping("/categorias")
        public List<CategoriaDto> categorias() {
            return categorias.listar(true);
        }

        @GetMapping("/productos")
        public PageResponse<ProductoVentaDto> buscar(@RequestParam(required = false) String q,
                                                     @RequestParam(required = false) Integer categoriaId,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "30") int size) {
            return productos.buscarParaVenta(q, categoriaId, page, size);
        }

        @GetMapping("/productos/codigo/{codigo}")
        public ProductoVentaDto porCodigo(@PathVariable String codigo) {
            return productos.porCodigoParaVenta(codigo);
        }

        @GetMapping("/productos/{id}")
        public ProductoVentaDto obtener(@PathVariable UUID id) {
            return productos.obtenerParaVenta(id);
        }
    }

    @RestController
    @RequestMapping("/api/v1/admin/categorias")
    @PreAuthorize("hasRole('ADMIN')")
    public static class CategoriaAdminController {
        private final CategoriaService servicio;

        public CategoriaAdminController(CategoriaService servicio) {
            this.servicio = servicio;
        }

        @GetMapping
        public List<CategoriaDto> listar() {
            return servicio.listar(false);
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        public CategoriaDto crear(@Valid @RequestBody CategoriaRequest r) {
            return servicio.crear(r);
        }

        @PutMapping("/{id}")
        public CategoriaDto actualizar(@PathVariable int id, @Valid @RequestBody CategoriaRequest r,
                                       @RequestParam(required = false) Boolean activa) {
            return servicio.actualizar(id, r, activa);
        }
    }

    @RestController
    @RequestMapping("/api/v1/admin/productos")
    @PreAuthorize("hasRole('ADMIN')")
    public static class ProductoAdminController {
        private final ProductoService servicio;

        public ProductoAdminController(ProductoService servicio) {
            this.servicio = servicio;
        }

        @GetMapping
        public PageResponse<ProductoAdminDto> listar(@RequestParam(required = false) String q,
                                                     @RequestParam(required = false) Integer categoriaId,
                                                     @RequestParam(required = false) Boolean activo,
                                                     @RequestParam(required = false) String abc,
                                                     @RequestParam(defaultValue = "false") boolean bajoStock,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "50") int size) {
            return servicio.buscarAdmin(q, categoriaId, activo, abc, bajoStock, page, size);
        }

        @GetMapping("/{id}")
        public ProductoAdminDto obtener(@PathVariable UUID id) {
            return servicio.obtenerAdmin(id);
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        public ProductoAdminDto crear(@Valid @RequestBody ProductoRequest r) {
            return servicio.crear(r);
        }

        @PutMapping("/{id}")
        public ProductoAdminDto actualizar(@PathVariable UUID id, @Valid @RequestBody ProductoActualizarRequest r) {
            return servicio.actualizar(id, r);
        }

        @PostMapping("/{id}/baja")
        public ProductoAdminDto baja(@PathVariable UUID id) {
            return servicio.cambiarEstado(id, false);
        }

        @PostMapping("/{id}/reactivar")
        public ProductoAdminDto reactivar(@PathVariable UUID id) {
            return servicio.cambiarEstado(id, true);
        }
    }

    @RestController
    @RequestMapping("/api/v1/admin/proveedores")
    @PreAuthorize("hasRole('ADMIN')")
    public static class ProveedorAdminController {
        private final ProveedorService servicio;

        public ProveedorAdminController(ProveedorService servicio) {
            this.servicio = servicio;
        }

        @GetMapping
        public PageResponse<ProveedorDto> listar(@RequestParam(required = false) String q,
                                                 @RequestParam(required = false) Boolean activo,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "50") int size) {
            return servicio.listar(q, activo, page, size);
        }

        @GetMapping("/{id}")
        public ProveedorDto obtener(@PathVariable UUID id) {
            return servicio.obtener(id);
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        public ProveedorDto crear(@Valid @RequestBody ProveedorRequest r) {
            return servicio.crear(r);
        }

        @PutMapping("/{id}")
        public ProveedorDto actualizar(@PathVariable UUID id, @Valid @RequestBody ProveedorRequest r) {
            return servicio.actualizar(id, r);
        }

        @PostMapping("/{id}/desactivar")
        public ProveedorDto desactivar(@PathVariable UUID id) {
            return servicio.cambiarEstado(id, false);
        }

        @PostMapping("/{id}/reactivar")
        public ProveedorDto reactivar(@PathVariable UUID id) {
            return servicio.cambiarEstado(id, true);
        }

        @GetMapping("/{id}/productos")
        public List<ProveedorProductoDto> productos(@PathVariable UUID id) {
            return servicio.productosDe(id);
        }

        @PutMapping("/{id}/productos")
        public List<ProveedorProductoDto> reemplazarProductos(@PathVariable UUID id,
                                                              @RequestBody List<@Valid ProveedorProductoRequest> items) {
            return servicio.reemplazarProductos(id, items);
        }
    }
}
