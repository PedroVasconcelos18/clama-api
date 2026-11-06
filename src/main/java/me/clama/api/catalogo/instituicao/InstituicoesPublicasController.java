package me.clama.api.catalogo.instituicao;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** InstituicaoListView: ativas, ordem e nome, sem paginacao. O throttle anonimo vem do InterceptorDeTaxa. */
@RestController
class InstituicoesPublicasController {
    private final InstituicaoRepository instituicoes;

    InstituicoesPublicasController(InstituicaoRepository instituicoes) {
        this.instituicoes = instituicoes;
    }

    @GetMapping("/api/instituicoes/")
    List<InstituicaoDtos.Publico> listar() {
        return instituicoes.findByAtivoTrueOrderByOrdemAscNomeAsc().stream().map(InstituicaoDtos.Publico::de)
                .toList();
    }
}
