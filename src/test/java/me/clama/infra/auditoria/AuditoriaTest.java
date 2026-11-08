package me.clama.infra.auditoria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import me.clama.BancoDeTeste;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(BancoDeTeste.class)
class AuditoriaTest {

	@Autowired Auditoria auditoria;
	@Autowired TransactionTemplate transacao;
	@Autowired JdbcClient jdbc;

	private long linhas(String entidadeId) {
		return jdbc.sql("select count(*) from auditoria where entidade_id = :id").param("id", entidadeId)
				.query(Long.class).single();
	}

	@Test
	void gravaNaTransacaoDoChamador() {
		var id = UUID.randomUUID().toString();
		transacao.executeWithoutResult(s -> auditoria.registrar(7L, "usuario", id, Acao.MUDANCA,
				Map.of("ativo", Map.of("antes", true, "depois", false))));
		var linha = jdbc.sql("select autor_id, entidade, acao, diff->'ativo'->>'depois' as depois from auditoria where entidade_id = :id")
				.param("id", id).query().singleRow();
		assertThat(linha).containsEntry("autor_id", 7L).containsEntry("entidade", "usuario")
				.containsEntry("acao", "MUDANCA").containsEntry("depois", "false");
	}

	@Test
	void rollbackDoChamadorTiraALinha() {
		var id = UUID.randomUUID().toString();
		assertThatThrownBy(() -> transacao.executeWithoutResult(s -> {
			auditoria.registrar(null, "usuario", id, Acao.EXCLUSAO, Map.of());
			throw new IllegalStateException("falhou");
		})).isInstanceOf(IllegalStateException.class);
		assertThat(linhas(id)).isZero();
	}

	@Test
	void foraDeTransacaoFalha() {
		assertThatThrownBy(() -> auditoria.registrar(1L, "usuario", "x", Acao.ADICAO, Map.of()))
				.isInstanceOf(IllegalTransactionStateException.class);
	}

	@Test
	void senhaEHashNuncaVaoParaODiff() {
		var id = UUID.randomUUID().toString();
		transacao.executeWithoutResult(s -> auditoria.registrar(1L, "usuario", id, Acao.MUDANCA, Map.of(
				"senha", "segredo-123", "hash", "$argon2$abc", "SENHA", "outro-segredo",
				"aninhado", Map.of("senha", Map.of("antes", "velho", "depois", "novo")), "nome", "Maria")));
		var diff = jdbc.sql("select diff::text from auditoria where entidade_id = :id").param("id", id)
				.query(String.class).single();
		assertThat(diff).doesNotContain("segredo", "argon2", "velho", "novo").contains("alterada", "Maria");
	}
}
