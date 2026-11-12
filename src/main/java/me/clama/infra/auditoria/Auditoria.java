package me.clama.infra.auditoria;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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

	/** Uma linha da trilha: quando, quem (nulo = sistema), a acao e o diff. */
	public record Registro(Instant ocorridoEm, Long autorId, String acao, Map<String, Object> diff) {}

	/** O historico de uma entidade, do mais antigo ao mais novo. */
	@Transactional(readOnly = true)
	public List<Registro> daEntidade(String entidade, String id) {
		return jdbc.sql("""
				select ocorrido_em, autor_id, acao, diff::text diff from auditoria
				where entidade = :entidade and entidade_id = :id order by ocorrido_em, id""")
				.param("entidade", entidade).param("id", id)
				.query((rs, n) -> new Registro(rs.getObject("ocorrido_em", OffsetDateTime.class).toInstant(),
						rs.getObject("autor_id", Long.class), rs.getString("acao"), lerDiff(rs.getString("diff"))))
				.list();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> lerDiff(String json) {
		return JSON.readValue(json, Map.class);
	}

	public static final String REMOVIDO_NA_EXCLUSAO_LGPD = "[removido na exclusão LGPD]";

	/**
	 * Troca, nas linhas da entidade que tem o {@code campo} no diff, o valor por {@link #REMOVIDO_NA_EXCLUSAO_LGPD}: a
	 * linha (quem, quando, acao) fica. Devolve quantas mudou. Grava na transacao do chamador.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public int redigir(String entidade, String id, String campo) {
		return jdbc.sql("""
				update auditoria set diff = jsonb_set(diff, array[:campo]::text[], to_jsonb(cast(:texto as text)))
				where entidade = :entidade and entidade_id = :id and jsonb_exists(diff, :campo)""")
				.param("campo", campo).param("texto", REMOVIDO_NA_EXCLUSAO_LGPD).param("entidade", entidade).param("id", id).update();
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
