package me.clama.api.comum.erro;

import java.util.UUID;
import java.util.regex.Pattern;

/** Http404 do get_object_or_404 do DRF: {"detail": "No <Modelo> matches the given query."}. */
public class NaoEncontrado extends RuntimeException {
    /** converter uuid do Django: so minusculas. Outra coisa e URL desconhecida (404 "Não encontrado."). */
    private static final Pattern UUID_DJANGO =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    public static UUID uuidDaRota(String rota) {
        if (!UUID_DJANGO.matcher(rota).matches()) {
            throw new NaoEncontrado();
        }
        return UUID.fromString(rota);
    }

    /** URL que o Django nao resolve (ex. uuid mal formado). */
    public NaoEncontrado() {
        super("Não encontrado.", null, false, false);
    }

    /** PageNumberPagination do DRF: {"detail": "Página inválida."}. */
    public static NaoEncontrado paginaInvalida() {
        return new NaoEncontrado(null, "Página inválida.");
    }

    private NaoEncontrado(Void sentinela, String detalhe) {
        super(detalhe, null, false, false);
    }

    public NaoEncontrado(String modelo) {
        super("No " + modelo + " matches the given query.", null, false, false);
    }
}
