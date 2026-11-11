package me.clama.oracao.interno;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import me.clama.infra.auditoria.Acao;
import me.clama.infra.auditoria.Auditoria;
import me.clama.infra.web.ErroDeNegocio;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Escritas do admin sobre as versoes de prompt (D15), cada uma numa transacao curta e auditada. O texto de uma versao
 * nunca muda: salvar e inserir. Ativar e voltar ao padrao passam por uma trava consultiva, para duas ativacoes ao mesmo
 * tempo nao baterem no indice de "uma ativa" (que seria 500).
 */
@Component
public class AdminDePrompts {

	static final String ENTIDADE = "prompt_versao";

	public record Versao(long id, Prompt conteudo, Instant criadoEm, @Nullable Long criadoPor, boolean ativo) {}

	private final JdbcClient jdbc;
	private final Auditoria auditoria;
	private final TransactionTemplate transacao;
	private final Clock relogio;

	AdminDePrompts(JdbcClient jdbc, Auditoria auditoria, TransactionTemplate transacao, Clock relogio) {
		this.jdbc = jdbc;
		this.auditoria = auditoria;
		this.transacao = transacao;
		this.relogio = relogio;
	}

	/** Da mais nova para a mais antiga. */
	public List<Versao> listar() {
		return jdbc.sql("select * from prompt_versao order by id desc").query((rs, i) -> versao(rs)).list();
	}

	public Versao versao(long id) {
		return jdbc.sql("select * from prompt_versao where id = :id").param("id", id).query((rs, i) -> versao(rs)).optional()
				.orElseThrow(() -> new ErroDeNegocio(HttpStatus.NOT_FOUND, "prompt_nao_encontrado", "Versão de prompt não encontrada.",
						"Essa versão de prompt não foi encontrada."));
	}

	/** Nasce inativa. */
	public Versao criar(Long autorId, Prompt p) {
		return transacao.execute(s -> {
			var id = jdbc.sql("insert into prompt_versao (conteudo, criado_em, criado_por) values (cast(:c as jsonb), :em, :autor) returning id")
					.param("c", PromptVigente.json(p)).param("em", Timestamp.from(relogio.instant())).param("autor", autorId)
					.query(Long.class).single();
			auditoria.registrar(autorId, ENTIDADE, String.valueOf(id), Acao.ADICAO, Map.of());
			return versao(id);
		});
	}

	/** Desativa a atual (se houver) e ativa esta; ja ativa e no-op. */
	public Versao ativar(Long autorId, long id) {
		return transacao.execute(s -> {
			travar();
			var v = versao(id);
			if (v.ativo()) return v;
			desativarTodas(autorId);
			jdbc.sql("update prompt_versao set ativo = true where id = :id").param("id", id).update();
			auditoria.registrar(autorId, ENTIDADE, String.valueOf(id), Acao.MUDANCA, mudanca(false, true));
			return versao(id);
		});
	}

	/** Nenhuma ativa: a geracao volta ao padrao do codigo. Ja no padrao e no-op. */
	public void voltarAoPadrao(Long autorId) {
		transacao.executeWithoutResult(s -> {
			travar();
			desativarTodas(autorId);
		});
	}

	private void desativarTodas(Long autorId) {
		for (var id : jdbc.sql("update prompt_versao set ativo = false where ativo returning id").query(Long.class).list()) {
			auditoria.registrar(autorId, ENTIDADE, String.valueOf(id), Acao.MUDANCA, mudanca(true, false));
		}
	}

	private void travar() {
		jdbc.sql("select pg_advisory_xact_lock(hashtext('prompt_versao:ativacao'))").query().singleRow();
	}

	private static Map<String, Object> mudanca(boolean antes, boolean depois) {
		return Map.of("ativo", Map.of("antes", antes, "depois", depois));
	}

	private static Versao versao(ResultSet rs) throws SQLException {
		return new Versao(rs.getLong("id"), PromptVigente.ler(rs.getString("conteudo")), rs.getTimestamp("criado_em").toInstant(),
				rs.getObject("criado_por", Long.class), rs.getBoolean("ativo"));
	}
}
