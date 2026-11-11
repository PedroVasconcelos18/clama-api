package me.clama.oracao.interno;

import java.time.Instant;
import java.util.UUID;
import me.clama.infra.fila.Fila;
import me.clama.infra.fila.TipoDeJob;
import me.clama.pedido.GeracaoSolicitada;
import me.clama.pedido.ReenvioSolicitado;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Eventos do pedido viram jobs. {@code @EventListener} sincrono: roda na transacao de quem publicou (o {@code PedidoApi}),
 * entao o job so existe se a mudanca de status comitar, e nenhum pedido pago fica sem job. Ja na fila: so antecipa.
 */
@Component
class OuvinteDePedido {

	private final Fila fila;
	private final JobGerarOracao gerar;
	private final JobReenviarOracao reenviar;

	OuvinteDePedido(Fila fila, JobGerarOracao gerar, JobReenviarOracao reenviar) {
		this.fila = fila;
		this.gerar = gerar;
		this.reenviar = reenviar;
	}

	@EventListener
	void gerar(GeracaoSolicitada e) {
		enfileirarOuAntecipar(gerar, e.pedidoId(), e.pedidoId());
	}

	/** O {@code envio} novo faz a chave do e-mail nova: o reenvio sempre sai (D10). */
	@EventListener
	void reenviar(ReenvioSolicitado e) {
		enfileirarOuAntecipar(reenviar, e.pedidoId(), new JobReenviarOracao.Reenvio(e.pedidoId(), UUID.randomUUID()));
	}

	private <P> void enfileirarOuAntecipar(TipoDeJob<P> tipo, UUID id, P payload) {
		var instancia = tipo.instancia(id);
		if (!fila.enfileirar(tipo, instancia, payload, Instant.now())) fila.antecipar(tipo.getName(), instancia);
	}
}
