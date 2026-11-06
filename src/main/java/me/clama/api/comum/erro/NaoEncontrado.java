package me.clama.api.comum.erro;

/** Http404 do get_object_or_404 do DRF: {"detail": "No <Modelo> matches the given query."}. */
public class NaoEncontrado extends RuntimeException {
    /** URL que o Django nao resolve (ex. uuid mal formado). */
    public NaoEncontrado() {
        super("Não encontrado.", null, false, false);
    }

    public NaoEncontrado(String modelo) {
        super("No " + modelo + " matches the given query.", null, false, false);
    }
}
