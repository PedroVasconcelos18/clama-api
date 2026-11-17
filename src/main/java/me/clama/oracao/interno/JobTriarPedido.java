package me.clama.oracao.interno;

import java.time.Duration;
import java.util.UUID;
import me.clama.infra.fila.TipoDeJob;
import me.clama.pedido.PedidoApi;
import me.clama.pedido.PedidoNaoEncontradoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code triar-pedido/<pedido>}: a triagem de crise do pedido. Grava {@code pedido.risco} e, com risco, enfileira o
 * {@code alerta-admin} na mesma transacao. Sem resposta valida, a fila tenta de novo (3 vezes: 1, 2 e 4 min); ao desistir
 * o pedido fica como esta ({@code risco} nulo) e a geracao, que corre em paralelo, nao depende disto.
 */
@Component
class JobTriarPedido extends TipoDeJob<UUID> {

	private static final Logger LOG = LoggerFactory.getLogger(JobTriarPedido.class);

	private final PedidoApi pedidos;
	private final Triagem triagem;
	private final JobAlertaAdmin alerta;
	private final TransactionTemplate tx;

	JobTriarPedido(PedidoApi pedidos, Triagem triagem, JobAlertaAdmin alerta, TransactionTemplate tx) {
		super("triar-pedido", UUID.class, new Retentativas(3, Duration.ofMinutes(1), 2.0));
		this.pedidos = pedidos;
		this.triagem = triagem;
		this.alerta = alerta;
		this.tx = tx;
	}

	@Override
	protected void executar(UUID id, int tentativa) {
		String texto;
		try {
			texto = pedidos.dadosParaOracao(id).pedidoOracao();
		} catch (PedidoNaoEncontradoException e) {
			LOG.warn("triar-pedido: pedido nao existe mais (pedido_id={})", id);
			return;
		}
		var risco = triagem.avaliar(texto)
				.orElseThrow(() -> new IllegalStateException("triagem sem resposta valida (pedido_id=" + id + ")"));
		tx.executeWithoutResult(s -> {
			pedidos.registrarTriagem(id, risco);
			if (risco) alerta.enfileirar(id, "risco");
		});
		LOG.info("triar-pedido: pedido triado (pedido_id={}, tentativa={})", id, tentativa + 1);
	}

	@Override
	protected void aoDesistir(UUID id, Throwable erro) {
		LOG.warn("triar-pedido: desistiu, pedido segue sem triagem (pedido_id={})", id);
	}
}
