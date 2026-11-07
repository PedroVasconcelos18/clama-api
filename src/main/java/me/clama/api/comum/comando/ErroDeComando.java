package me.clama.api.comum.comando;

/** CommandError do Django: stderr "CommandError: &lt;mensagem&gt;" e exit 1, sem Sentry. */
public class ErroDeComando extends RuntimeException {
    public ErroDeComando(String mensagem) {
        super(mensagem);
    }
}
