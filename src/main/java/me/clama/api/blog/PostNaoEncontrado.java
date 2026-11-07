package me.clama.api.blog;

import me.clama.api.comum.erro.PastoralException;

/** O post nao existe nem no espelho nem no WordPress (espelho.py:PostNaoEncontrado). */
public class PostNaoEncontrado extends PastoralException {
    public PostNaoEncontrado() {
        super(404, "post_nao_encontrado", "Post não encontrado.",
                "Não encontramos este texto. Ele pode ter saído do ar.");
    }
}
