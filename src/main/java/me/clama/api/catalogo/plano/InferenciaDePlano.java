package me.clama.api.catalogo.plano;

import java.util.Optional;
import org.springframework.stereotype.Component;

/** PlanManager.infer_from_valor / fallback_valor_livre (clama/plans/models.py). */
@Component
public class InferenciaDePlano {
    private final PlanoRepository planos;

    public InferenciaDePlano(PlanoRepository planos) {
        this.planos = planos;
    }

    /** Ativo e visivel de maior valor <= centavos; senao o de menor valor; senao vazio. */
    public Optional<Plano> inferirPorValor(int centavos) {
        return planos.findFirstByAtivoTrueAndVisivelTrueAndValorCentavosLessThanEqualOrderByValorCentavosDesc(centavos)
                .or(planos::findFirstByAtivoTrueAndVisivelTrueOrderByValorCentavosAsc);
    }

    /** O plano "simples" invisivel e ativo (seed plans/0008). */
    public Optional<Plano> fallbackValorLivre() {
        return planos.findFirstByComplexidadeAndVisivelFalseAndAtivoTrueOrderByOrdem("simples");
    }
}
