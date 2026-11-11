package me.clama.blog.interno;

import java.time.Clock;
import java.time.OffsetDateTime;
import me.clama.infra.fila.TarefaRecorrente;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@code purgar-ips} (D12), as 03:00: o IP so existe pelo Marco Civil, e o Marco Civil (art. 15) manda guardar por no minimo 6 meses.
 * Um {@code UPDATE} so, sem decifrar nada: comentario com mais de 6 meses de calendario (fuso de Sao Paulo; exatamente 6 meses
 * fica) perde o {@code ip_cifrado}. Meses, nao 180 dias: 180 dias apagaria alguns dias antes do prazo.
 */
@Component
class PurgaDeIps extends TarefaRecorrente {

	private static final Logger LOG = LoggerFactory.getLogger(PurgaDeIps.class);
	static final int RETENCAO_EM_MESES = 6;

	private final JdbcClient jdbc;
	private final Clock relogio;

	PurgaDeIps(JdbcClient jdbc, Clock relogio) {
		super("purgar-ips", "0 0 3 * * *");
		this.jdbc = jdbc;
		this.relogio = relogio;
	}

	@Override
	protected void executar(boolean dryRun) {
		var corte = relogio.instant().atZone(FUSO).minusMonths(RETENCAO_EM_MESES).toOffsetDateTime();
		var onde = " from blog_comentario where criado_em < :corte and ip_cifrado is not null";
		if (dryRun) {
			var n = jdbc.sql("select count(*)" + onde).param("corte", corte).query(Long.class).single();
			LOG.info("purgar-ips (dry-run): {} IP(s) seriam apagados", n);
			return;
		}
		var n = jdbc.sql("update blog_comentario set ip_cifrado = null where criado_em < :corte and ip_cifrado is not null")
				.param("corte", corte).update();
		LOG.info("purgar-ips: {} IP(s) apagados", n);
	}
}
