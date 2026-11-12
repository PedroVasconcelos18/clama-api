package me.clama.conta.interno;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import me.clama.infra.fila.TarefaRecorrente;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@code purgar-links-de-senha} (D15), 04:30: apaga o link de senha vencido ha mais de 7 dias (so um hash de credencial
 * guardado a toa). Os 7 dias mantem a mensagem "o link expirou" por uma semana.
 */
@Component
class PurgaDeLinksDeSenha extends TarefaRecorrente {

	private static final Logger LOG = LoggerFactory.getLogger(PurgaDeLinksDeSenha.class);
	static final Duration CARENCIA = Duration.ofDays(7);

	private final JdbcClient jdbc;
	private final Clock relogio;

	PurgaDeLinksDeSenha(JdbcClient jdbc, Clock relogio) {
		super("purgar-links-de-senha", "0 30 4 * * *");
		this.jdbc = jdbc;
		this.relogio = relogio;
	}

	@Override
	protected String executar(boolean dryRun) {
		var corte = OffsetDateTime.ofInstant(relogio.instant().minus(CARENCIA), ZoneOffset.UTC);
		if (dryRun) {
			int n = jdbc.sql("select count(*) from token_de_senha where expira_em < :corte").param("corte", corte).query(Integer.class).single();
			LOG.info("purgar-links-de-senha (dry-run): {} link(s) seriam apagados", n);
			return n + " link(s) seriam apagados (simulação)";
		}
		int n = jdbc.sql("delete from token_de_senha where expira_em < :corte").param("corte", corte).update();
		LOG.info("purgar-links-de-senha: {} link(s) apagados", n);
		return n > 0 ? n + " link(s) apagados" : "nada a apagar";
	}
}
