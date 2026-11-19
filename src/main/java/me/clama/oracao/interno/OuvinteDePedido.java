package me.clama.oracao.interno;

import java.time.Instant;
import java.util.UUID;
import me.clama.infra.fila.Fila;
import me.clama.infra.fila.TipoDeJob;
import me.clama.pedido.GeracaoSolicitada;
import me.clama.pedido.PedidoRegistrado;
import me.clama.pedido.ReenvioSolicitado;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Eventos do pedido viram jobs. {@code @EventListener} sincrono: roda na transacao de quem publicou (o {@code PedidoApi}),
 * entao o job so existe se a mudanca de status comitar, e nenhum pedido pago fica sem job. Ja na fila: so antecipa.
 */
@Component
class OuvinteDePedido {

	private static final Logger LOG = LoggerFactory.getLogger(OuvinteDePedido.class);

	private final Fila fila;
	private final JobGerarOracao gerar;
	private final JobEnviarOracao enviar;
	private final JobReenviarOracao reenviar;
	private final JobTriarPedido triar;

	OuvinteDePedido(Fila fila, JobGerarOracao gerar, JobEnviarOracao enviar, JobReenviarOracao reenviar, JobTriarPedido triar) {
		this.fila = fila;
		this.triar = triar;
		this.gerar = gerar;
		this.enviar = enviar;
		this.reenviar = reenviar;
	}

	@EventListener
	void gerar(GeracaoSolicitada e) {
		enfileirarOuAntecipar(gerar, e.pedidoId(), e.pedidoId());
	}

	/** Todo pedido novo e triado; em paralelo a geracao, que nao espera a triagem. */
	@EventListener
	void triar(PedidoRegistrado e) {
		enfileirarOuAntecipar(triar, e.pedidoId(), e.pedidoId());
	}

	/**
	 * O {@code envio} novo faz a chave do e-mail nova: o reenvio sempre sai (D10). Com a geracao ou o envio ainda na fila
	 * e no-op, como no antigo: esse job ja vai entregar a oracao.
	 */
	@EventListener
	void reenviar(ReenvioSolicitado e) {
		var id = e.pedidoId();
		if (fila.existePendente(gerar.getName(), gerar.instancia(id)) || fila.existePendente(enviar.getName(), enviar.instancia(id))) {
			LOG.info("reenvio: geracao ou envio ainda na fila, nada enfileirado (pedido_id={})", id);
			return;
		}
		enfileirarOuAntecipar(reenviar, e.pedidoId(), new JobReenviarOracao.Reenvio(e.pedidoId(), UUID.randomUUID()));
	}

	private <P> void enfileirarOuAntecipar(TipoDeJob<P> tipo, UUID id, P payload) {
		var instancia = tipo.instancia(id);
		if (!fila.enfileirar(tipo, instancia, payload, Instant.now())) fila.antecipar(tipo.getName(), instancia);
	}
}
