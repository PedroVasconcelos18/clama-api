package me.clama.api.catalogo.plano;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import me.clama.api.comum.erro.PastoralException;
import org.springframework.stereotype.Component;

/**
 * _get_plano_gratuito (freemium/api/views.py:150-177), usado pelo gratuito autenticado e pelo freemium. Ausente: evento
 * Sentry de nivel error + 503 pastoral. O Django faz get() (dois planos = 500); aqui o 1o por ordem (aceito na 4B).
 */
@Component
public class PlanoGratuito {
    private final PlanoRepository planos;

    public PlanoGratuito(PlanoRepository planos) {
        this.planos = planos;
    }

    public Plano obter() {
        return planos.findFirstByComplexidadeAndVisivelFalseAndAtivoTrueOrderByOrdem("simples_gratuita")
                .orElseThrow(() -> {
                    Sentry.captureMessage("Plan freemium ausente do banco (SIMPLES_GRATUITA, visivel=False, ativo=True)", SentryLevel.ERROR);
                    return new PastoralException(503, "freemium_indisponivel", "Plano freemium não configurado",
                            "Estamos com problemas momentâneos. Tente em alguns minutos.");
                });
    }
}
