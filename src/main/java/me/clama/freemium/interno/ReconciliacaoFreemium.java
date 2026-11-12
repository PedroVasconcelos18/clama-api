package me.clama.freemium.interno;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import me.clama.infra.fila.TarefaRecorrente;
import me.clama.pedido.PedidoApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
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
	/** D15: o link de confirmacao vencido ha mais de 7 dias e apagado (so um hash guardado a toa). */
	static final Duration CARENCIA_DO_LINK = Duration.ofDays(7);

	private final PedidoApi pedidos;
	private final Clock relogio;
	private final JdbcClient jdbc;

	ReconciliacaoFreemium(PedidoApi pedidos, Clock relogio, JdbcClient jdbc) {
		super("reconciliar-freemium", "0 17 */6 * * *");
		this.pedidos = pedidos;
		this.relogio = relogio;
		this.jdbc = jdbc;
	}

	@Override
	protected String executar(boolean dryRun) {
		var corte = relogio.instant().minus(JANELA);
		var corteDoLink = OffsetDateTime.ofInstant(relogio.instant().minus(CARENCIA_DO_LINK), ZoneOffset.UTC);
		if (dryRun) {
			var n = pedidos.contarConfirmacoesVencidas(corte);
			int links = jdbc.sql("select count(*) from freemium_confirmacao where expira_em < :corte").param("corte", corteDoLink).query(Integer.class).single();
			LOG.info("reconciliar-freemium (dry-run): {} pedido(s) expirariam, {} link(s) seriam apagados", n, links);
			return n + " pedido(s) expirariam (simulação)" + (links > 0 ? "; " + links + " link(s) seriam apagados" : "");
		}
		int n = pedidos.expirarConfirmacoes(corte);
		if (n > 0) {
			LOG.warn("reconciliar-freemium: {} pedido(s) sem confirmacao ha mais de 48 h foram para CONFIRMACAO_EXPIRADA", n);
			Sentry.captureMessage("reconciliar-freemium: " + n + " pedido(s) com confirmacao expirada", SentryLevel.WARNING);
		} else {
			LOG.info("reconciliar-freemium: nada a expirar");
		}
		int links = jdbc.sql("delete from freemium_confirmacao where expira_em < :corte").param("corte", corteDoLink).update();
		if (links > 0) LOG.info("reconciliar-freemium: {} link(s) vencidos ha mais de 7 dias apagados", links);
		var resumo = n > 0 ? n + " pedido(s) expirados" : "nada a expirar";
		return links > 0 ? resumo + "; " + links + " link(s) apagados" : resumo;
	}
}
