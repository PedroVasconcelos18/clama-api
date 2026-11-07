package me.clama.api.blog;

import me.clama.api.comum.erro.PastoralException;

/** O post existe, mas nao esta publicado: 409, nao 403 (espelho.py:PostNaoAceitaInteracao). */
public class PostNaoAceitaInteracao extends PastoralException {
    public PostNaoAceitaInteracao() {
        super(409, "post_nao_aceita_interacao", "Post não está publicado.",
                "Este texto saiu do ar enquanto você escrevia. "
                        + "Copiamos o que você digitou — guarde, e tente de novo quando ele voltar.");
    }
}
