package me.clama.freemium.interno;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import me.clama.BancoDeTeste;
import me.clama.CaixaDeSaidaEmMemoria;
import me.clama.EmailDeTeste;
import me.clama.infra.fila.Fila;
import me.clama.infra.fila.FilaLigada;
import me.clama.pedido.NovoPedido;
import me.clama.pedido.PedidoApi;
import me.clama.pedido.StatusPedido;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/** O job do e-mail de confirmacao (D5, D12): o link so existe no e-mail, e o banco guarda o hash. */
@SpringBootTest
@Import({ BancoDeTeste.class, EmailDeTeste.class })
@ActiveProfiles("test")
@FilaLigada
@ExtendWith(OutputCaptureExtension.class)
class JobConfirmacaoFreemiumTest {

	static final Pattern LINK = Pattern.compile("http://localhost:5173/confirmar#token=([A-Za-z0-9_-]+)");

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		r.add("clama.freemium.espera-email", () -> "100ms"); // retentativas em 100, 200 e 400 ms
	}

	@Autowired JdbcTemplate jdbc;
	@Autowired PedidoApi pedidos;
	@Autowired JobConfirmacaoFreemium job;
	@Autowired Confirmacoes confirmacoes;
	@Autowired PedidoFreemium submit;
	@Autowired Fila fila;
	@Autowired CaixaDeSaidaEmMemoria caixa;
	@Autowired TransactionTemplate tx;

	@BeforeEach
	@AfterEach
	void limpar() {
		caixa.limpar();
		jdbc.update(BancoDeTeste.LIMPAR_FILA);
		jdbc.update("delete from pedido"); // freemium_confirmacao cai em cascata
		jdbc.update("delete from freemium_bloqueio");
	}

	String email = "frm-" + UUID.randomUUID() + "@exemplo.com";

	UUID pedido() {
		return pedidos.criarFreemium(new NovoPedido("Maria Souza", email, "529.982.247-25", null, null, null, "Pela família", null, null,
				null, true, null, null), "203.0.113.9");
	}

	String tokenDe(CaixaDeSaidaEmMemoria.Enviado e) {
		var m = LINK.matcher(e.email().texto());
		assertThat(m.find()).as(e.email().texto()).isTrue();
		return m.group(1);
	}

	int linhas(UUID id) {
		return jdbc.queryForObject("select count(*) from freemium_confirmacao where pedido_id = ?", Integer.class, id);
	}

	@Test
	void emailComAssuntoNomeLinkE24HorasSemPiiNoLog(CapturedOutput log) {
		var id = pedido();
		job.executar(id, 0);

		var enviados = caixa.para(email);
		assertThat(enviados).hasSize(1);
		var e = enviados.getFirst();
		var token = tokenDe(e);
		assertThat(e.email().assunto()).isEqualTo("Confirme sua oração — clama");
		assertThat(e.email().texto()).contains("Paz e bem, Maria.", "expira em 24 horas");
		assertThat(e.email().html()).contains("Maria", "/confirmar#token=" + token, "24");
		assertThat(token).hasSize(43); // 32 bytes em base64url, sem padding
		assertThat(e.chave()).isEqualTo("confirmacao-freemium/" + Confirmacoes.hash(token));
		assertThat(caixa.para("contato@clama.me")).isEmpty();
		assertThat(log.getAll()).doesNotContain(email, token, "Maria Souza", "52998224725");
		assertThat(pedidos.resumo(id).orElseThrow().status()).isEqualTo(StatusPedido.AGUARDANDO_CONFIRMACAO_EMAIL);
	}

	/** Review Focus 3: o token nunca vai ao banco, so o SHA-256, com a validade de 24 h. */
	@Test
	void noBancoSoOHashNuncaOToken() {
		var id = pedido();
		job.executar(id, 0);
		var token = tokenDe(caixa.para(email).getFirst());

		assertThat(jdbc.queryForObject("select hash from freemium_confirmacao where pedido_id = ?", String.class, id))
				.isEqualTo(Confirmacoes.hash(token)).hasSize(64).doesNotContain(token);
		var tudo = jdbc.queryForList("select row_to_json(c)::text from freemium_confirmacao c", String.class)
				+ jdbc.queryForList("select row_to_json(t)::text from scheduled_tasks t", String.class).toString()
				+ jdbc.queryForList("select row_to_json(p)::text from pedido p", String.class);
		assertThat(tudo).doesNotContain(token);
		assertThat(jdbc.queryForObject("""
				select extract(epoch from expira_em - criado_em) from freemium_confirmacao where pedido_id = ?""", Long.class, id))
				.isEqualTo(24 * 3600L);
		assertThat(jdbc.queryForObject("select usado_em is null from freemium_confirmacao where pedido_id = ?", Boolean.class, id)).isTrue();
	}

	@Test
	void retentativaEmiteOutroTokenEOsDoisValem() {
		var id = pedido();
		job.executar(id, 0);
		job.executar(id, 1);

		var enviados = caixa.para(email);
		assertThat(enviados).hasSize(2);
		var t1 = tokenDe(enviados.get(0));
		var t2 = tokenDe(enviados.get(1));
		assertThat(t1).isNotEqualTo(t2);
		assertThat(enviados.get(0).chave()).isNotEqualTo(enviados.get(1).chave());
		assertThat(linhas(id)).isEqualTo(2);
		for (var t : new String[] { t1, t2 }) {
			var c = tx.execute(s -> confirmacoes.travarPorHash(Confirmacoes.hash(t)).orElseThrow());
			assertThat(c.pedidoId()).isEqualTo(id);
			assertThat(c.usado()).isFalse();
			assertThat(c.vencido(Instant.now())).isFalse();
		}
		// travar e gastar: so o link usado muda
		tx.executeWithoutResult(s -> confirmacoes.marcarUsado(confirmacoes.travarPorHash(Confirmacoes.hash(t1)).orElseThrow().id()));
		assertThat(Boolean.TRUE.equals(tx.execute(s -> confirmacoes.travarPorHash(Confirmacoes.hash(t1)).orElseThrow().usado()))).isTrue();
		assertThat(Boolean.TRUE.equals(tx.execute(s -> confirmacoes.travarPorHash(Confirmacoes.hash(t2)).orElseThrow().usado()))).isFalse();
		assertThat(Boolean.TRUE.equals(tx.execute(s -> confirmacoes.travarPorHash(Confirmacoes.hash("nao-existe")).isPresent()))).isFalse();
	}

	@Test
	void pedidoForaDoEstadoOuInexistenteENoop() {
		var id = pedido();
		jdbc.update("update pedido set status = 'PAGO' where id = ?", id);
		job.executar(id, 0);
		jdbc.update("update pedido set status = 'CONFIRMACAO_EXPIRADA' where id = ?", id);
		job.executar(id, 0);
		job.executar(UUID.randomUUID(), 0); // inexistente: nao lanca

		assertThat(caixa.enviados()).isEmpty();
		assertThat(linhas(id)).isZero();
	}

	@Test
	void submitEnfileiraEOJobDaFilaEnviaOEmail() {
		var id = submit.submeter(new NovoPedido("Maria Souza", email, "529.982.247-25", null, null, null, null, null, null, null, true, null, null),
				"203.0.113.9");

		var enviados = caixa.aguardarPara(email, 1);
		assertThat(enviados).hasSize(1);
		assertThat(enviados.getFirst().chave()).startsWith("confirmacao-freemium/");
		assertThat(linhas(id)).isOne();
		await().atMost(Duration.ofSeconds(5)).until(() -> !fila.existePendente("confirmacao-freemium", "confirmacao-freemium/" + id));
		// Review Focus 1
		assertThat(jdbc.queryForObject("select count(*) from usuario where email = ?", Integer.class, email)).isZero();
		assertThat(caixa.enviados()).hasSize(1);
	}

	/** Esgotadas as 3 retentativas: so log e Sentry; sem alerta ao admin e sem mudar o status. */
	@Test
	void desistenciaNaoMudaOStatusNemAlertaOAdmin() {
		var id = pedido();
		caixa.falhar(true);
		tx.executeWithoutResult(s -> fila.enfileirar(job, job.instancia(id), id, Instant.now()));

		await().atMost(Duration.ofSeconds(20)).until(() -> !fila.existePendente("confirmacao-freemium", job.instancia(id)));

		caixa.falhar(false);
		assertThat(caixa.enviados()).isEmpty();
		assertThat(pedidos.resumo(id).orElseThrow().status()).isEqualTo(StatusPedido.AGUARDANDO_CONFIRMACAO_EMAIL);
		assertThat(linhas(id)).as("1 tentativa + 3 retentativas, cada uma com seu link").isEqualTo(4);
		assertThat(jdbc.queryForObject("select count(*) from scheduled_tasks where task_name not in ('confirmacao-freemium', 'triar-pedido')", Integer.class))
				.as("nenhum job de alerta").isZero();
	}
}
