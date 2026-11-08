package me.clama.infra.auditoria;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Trilha de auditoria. Sem FK para ninguem (apagar o usuario nao apaga a trilha) e sem tipo de nenhum modulo: so
 * strings e mapas. Grava na transacao do chamador (MANDATORY): se a escrita desfaz, a linha some junto.
 */
@Component
public class Auditoria {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final JdbcClient jdbc;
	private final Clock relogio;

	Auditoria(JdbcClient jdbc, Clock relogio) {
		this.jdbc = jdbc;
		this.relogio = relogio;
	}

	/** {@code autorId} nulo = o sistema (resgate no boot). {@code diff}: {campo: {antes, depois}}; senha e hash viram "alterada". */
	@Transactional(propagation = Propagation.MANDATORY)
	public void registrar(Long autorId, String entidade, String entidadeId, Acao acao, Map<String, Object> diff) {
		jdbc.sql("""
				insert into auditoria (ocorrido_em, autor_id, entidade, entidade_id, acao, diff)
				values (:em, :autor, :entidade, :id, :acao, cast(:diff as jsonb))""")
				.param("em", OffsetDateTime.ofInstant(relogio.instant(), ZoneOffset.UTC))
				.param("autor", autorId).param("entidade", entidade).param("id", entidadeId)
				.param("acao", acao.name()).param("diff", JSON.writeValueAsString(semSegredos(diff))).update();
	}

	private static Map<String, Object> semSegredos(Map<String, Object> diff) {
		var limpo = new LinkedHashMap<String, Object>();
		diff.forEach((chave, valor) -> {
			if (valor != null && (chave.equalsIgnoreCase("senha") || chave.equalsIgnoreCase("hash"))) {
				limpo.put(chave, "alterada");
			} else if (valor instanceof Map<?, ?> filho) {
				@SuppressWarnings("unchecked")
				var mapa = (Map<String, Object>) filho;
				limpo.put(chave, semSegredos(mapa));
			} else {
				limpo.put(chave, valor);
			}
		});
		return limpo;
	}
}
