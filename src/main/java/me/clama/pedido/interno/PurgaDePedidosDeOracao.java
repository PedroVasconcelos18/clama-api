package me.clama.pedido.interno;

import java.time.Clock;
import java.time.Duration;
import me.clama.infra.fila.TarefaRecorrente;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code purgar-pedidos-de-oracao} (D15, pergunta 1), 03:15: zera o texto do pedido ({@code pedido_oracao}) 30 dias depois
 * de {@code enviada_em}. <b>A oracao fica</b>: "Minha conta" a mostra e o reenvio usa so ela. Irreversivel: o
 * {@code application.yml} traz {@code dry-run: true} ate o Pedro confirmar.
 */
@Component
class PurgaDePedidosDeOracao extends TarefaRecorrente {

	private static final Logger LOG = LoggerFactory.getLogger(PurgaDePedidosDeOracao.class);
	static final Duration CARENCIA = Duration.ofDays(30);

	private final PedidoRepository pedidos;
	private final Clock relogio;

	PurgaDePedidosDeOracao(PedidoRepository pedidos, Clock relogio) {
		super("purgar-pedidos-de-oracao", "0 15 3 * * *");
		this.pedidos = pedidos;
		this.relogio = relogio;
	}

	@Override
	protected String executar(boolean dryRun) {
		var corte = relogio.instant().minus(CARENCIA);
		if (dryRun) {
			int n = pedidos.contarTextosParaPurgar(corte);
			LOG.info("purgar-pedidos-de-oracao (dry-run): {} texto(s) seriam apagados", n);
			return n + " texto(s) de pedido seriam apagados (simulação)";
		}
		int n = pedidos.purgarTextos(corte);
		LOG.info("purgar-pedidos-de-oracao: {} texto(s) de pedido apagados", n);
		return n > 0 ? n + " texto(s) de pedido apagados" : "nada a apagar";
	}
}
