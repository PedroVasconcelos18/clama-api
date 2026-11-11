package me.clama.pedido;

/** Onde o pedido esta. So muda pelos metodos de intencao do {@code Pedido}. */
public enum StatusPedido {
	AGUARDANDO_PAGAMENTO, PAGO, GERANDO_ORACAO, ORACAO_GERADA, ENVIADA, AGUARDANDO_NOVA_TENTATIVA, ERRO
}
