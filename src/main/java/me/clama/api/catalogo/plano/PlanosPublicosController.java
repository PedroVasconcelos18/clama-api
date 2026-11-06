package me.clama.api.catalogo.plano;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** PlanListView: ativos e visiveis, por ordem, sem paginacao. O throttle anonimo vem do InterceptorDeTaxa. */
@RestController
class PlanosPublicosController {
    private final PlanoRepository planos;

    PlanosPublicosController(PlanoRepository planos) {
        this.planos = planos;
    }

    @GetMapping("/api/planos/")
    List<PlanoDtos.Publico> listar() {
        return planos.findByAtivoTrueAndVisivelTrueOrderByOrdem().stream().map(PlanoDtos.Publico::de).toList();
    }
}
