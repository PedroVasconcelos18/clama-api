package me.clama.freemium.interno;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.time.Clock;
import java.time.Duration;
import me.clama.infra.fila.TarefaRecorrente;
import me.clama.pedido.PedidoApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code reconciliar-freemium} (D8), 00:17, 06:17, 12:17 e 18:17: pedido aguardando a confirmacao ha mais de 48 h vai a
 * CONFIRMACAO_EXPIRADA (terminal, nao e erro). Um WARN e um evento agregado no Sentry quando algo muda; nunca um alerta
 * por pedido e nunca dados pessoais.
 */
@Component
class ReconciliacaoFreemium extends TarefaRecorrente {

	private static final Logger LOG = LoggerFactory.getLogger(ReconciliacaoFreemium.class);
	static final Duration JANELA = Duration.ofHours(48);

	private final PedidoApi pedidos;
	private final Clock relogio;

	ReconciliacaoFreemium(PedidoApi pedidos, Clock relogio) {
		super("reconciliar-freemium", "0 17 */6 * * *");
		this.pedidos = pedidos;
		this.relogio = relogio;
	}

	@Override
	protected String executar(boolean dryRun) {
		var corte = relogio.instant().minus(JANELA);
		if (dryRun) {
			var n = pedidos.contarConfirmacoesVencidas(corte);
			LOG.info("reconciliar-freemium (dry-run): {} pedido(s) expirariam", n);
			return n + " pedido(s) expirariam (simulação)";
		}
		int n = pedidos.expirarConfirmacoes(corte);
		if (n > 0) {
			LOG.warn("reconciliar-freemium: {} pedido(s) sem confirmacao ha mais de 48 h foram para CONFIRMACAO_EXPIRADA", n);
			Sentry.captureMessage("reconciliar-freemium: " + n + " pedido(s) com confirmacao expirada", SentryLevel.WARNING);
		} else {
			LOG.info("reconciliar-freemium: nada a expirar");
		}
		return n > 0 ? n + " pedido(s) expirados" : "nada a expirar";
	}
}
