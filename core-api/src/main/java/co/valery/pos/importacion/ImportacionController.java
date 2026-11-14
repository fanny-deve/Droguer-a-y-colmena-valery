package co.valery.pos.importacion;

import co.valery.pos.common.ApiException;
import co.valery.pos.common.PageResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin/importaciones")
@PreAuthorize("hasRole('ADMIN')")
public class ImportacionController {
    private final ImportacionService servicio;

    public ImportacionController(ImportacionService servicio) {
        this.servicio = servicio;
    }

    @GetMapping("/plantilla/{tipo}")
    public ResponseEntity<byte[]> plantilla(@PathVariable String tipo) {
        byte[] cuerpo = ("\uFEFF" + servicio.plantilla(tipo)).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("plantilla_" + tipo + ".csv").build().toString())
                .body(cuerpo);
    }

    @PostMapping(value = "/catalogo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportacionService.ResultadoImportacion catalogo(@RequestParam("file") MultipartFile file) throws IOException {
        return servicio.importarCatalogo(file.getOriginalFilename(), leer(file));
    }

    @PostMapping(value = "/historico-ventas", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportacionService.ResultadoImportacion historico(@RequestParam("file") MultipartFile file) throws IOException {
        return servicio.importarHistorico(file.getOriginalFilename(), leer(file));
    }

    @GetMapping
    public PageResponse<ImportacionService.ImportacionDto> listar(@RequestParam(defaultValue = "0") int page,
                                                                  @RequestParam(defaultValue = "30") int size) {
        return servicio.listar(page, size);
    }

    @GetMapping("/{id}/errores")
    public List<ImportacionService.ErrorFila> errores(@PathVariable UUID id) {
        return servicio.errores(id);
    }

    private static byte[] leer(MultipartFile f) throws IOException {
        if (f == null || f.isEmpty()) throw ApiException.badRequest("ARCHIVO_VACIO", "Debe adjuntar un archivo CSV");
        return f.getBytes();
    }
}
