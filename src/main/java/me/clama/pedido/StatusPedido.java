package me.clama.pedido;

/** Onde o pedido esta. So muda pelos metodos de intencao do {@code Pedido}. */
public enum StatusPedido {
	AGUARDANDO_PAGAMENTO, PAGO, GERANDO_ORACAO, ORACAO_GERADA, ENVIADA, AGUARDANDO_NOVA_TENTATIVA, ERRO,
	/** Freemium: criado, esperando o clique no link do e-mail. Nao gera oracao. */
	AGUARDANDO_CONFIRMACAO_EMAIL,
	/** Freemium: o link nunca foi usado e a reconciliacao desistiu. Terminal. */
	CONFIRMACAO_EXPIRADA
}
