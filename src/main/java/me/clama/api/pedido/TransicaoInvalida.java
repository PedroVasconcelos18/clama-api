package me.clama.api.pedido;

/** Transicao de status fora do guard do model; a 4B mapeia para 409 invalid_state_transition. */
public class TransicaoInvalida extends RuntimeException {
    public TransicaoInvalida(String mensagem) {
        super(mensagem);
    }
}
