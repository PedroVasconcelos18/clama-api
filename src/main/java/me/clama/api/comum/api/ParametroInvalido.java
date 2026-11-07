package me.clama.api.comum.api;

/** Filtro malformado: o Django da 500, aqui 400 {"detail": "Parâmetro <nome> inválido."} (PastoralExceptionHandler). */
public class ParametroInvalido extends RuntimeException {
    public ParametroInvalido(String nome) {
        super("Parâmetro " + nome + " inválido.", null, false, false);
    }
}
