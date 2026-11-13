package co.valery.pos.analitica;

import co.valery.pos.common.PageResponse;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/analitica/abc")
@PreAuthorize("hasRole('ADMIN')")
public class AnaliticaController {
    private final AbcService abc;

    public AnaliticaController(AbcService abc) {
        this.abc = abc;
    }

    @PostMapping("/calcular")
    public AbcService.ResultadoAbc calcular() {
        return abc.calcular();
    }

    @GetMapping
    public PageResponse<AbcService.FilaAbc> consultar(@RequestParam(required = false) String categoria,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "50") int size) {
        return abc.consultar(categoria, page, size);
    }

    @GetMapping("/resumen")
    public List<AbcService.ResumenAbc> resumen() {
        return abc.resumen();
    }
}
