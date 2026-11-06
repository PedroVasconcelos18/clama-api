package me.clama.api.oracao;

/** PrayerGenerationError (clama/prayer_generation/exceptions.py): a geracao pela Claude falhou. */
public class ErroDeGeracao extends RuntimeException {
    public ErroDeGeracao(String mensagem) {
        super(mensagem);
    }

    public ErroDeGeracao(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
