package me.clama.api.oracao;

/**
 * InsufficientCreditsError: 400 da Anthropic com "credit balance". Falha persistente, sem retry: o pedido vai para
 * erro com last_error "credit_balance" e espera o admin recarregar e reenviar.
 */
public class CreditosEsgotados extends ErroDeGeracao {
    public CreditosEsgotados(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
