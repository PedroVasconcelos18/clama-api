package me.clama.blog.interno;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import me.clama.BancoDeTeste;
import me.clama.CaixaDeSaidaEmMemoria;
import me.clama.EmailDeTeste;
import me.clama.conta.ContaApi;
import me.clama.infra.alerta.AlertaAoAdmin;
import me.clama.infra.email.EnviadorDeEmail;
import me.clama.infra.email.ModelosDeEmail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@code alerta-comentarios} (D12): assunto, linhas, suspeitos primeiro, sem e-mail de quem comentou, janela de 24 h, zero, dry-run, chave do dia. */
@SpringBootTest
@Import({ BancoDeTeste.class, EmailDeTeste.class })
@ExtendWith(OutputCaptureExtension.class)
class AlertaDeComentariosTest {

	static final Instant AGORA = Instant.parse("2026-11-12T11:00:00Z"); // 08:00 em Sao Paulo
	static final String EMAIL_AUTORA = "alerta-ana@exemplo.com";

	@Autowired JdbcTemplate jdbc;
	@Autowired JdbcClient jdbcClient;
	@Autowired PostRepository posts;
	@Autowired ContaApi conta;
	@Autowired ModelosDeEmail modelos;
	@Autowired CaixaDeSaidaEmMemoria caixa;
	@Autowired AlertaAoAdmin.Props props;

	AlertaDeComentarios tarefa;
	Long autora;

	@AfterEach
	void limpar() {
		caixa.limpar();
		jdbc.update("delete from blog_comentario");
		jdbc.update("delete from blog_post");
		jdbc.update("delete from usuario where email like 'alerta-%'");
	}

	@BeforeEach
	void preparar() {
		limpar();
		tarefa = new AlertaDeComentarios(jdbcClient, conta, modelos, (EnviadorDeEmail) caixa, props, Clock.fixed(AGORA, ZoneOffset.UTC));
		posts.upsert(7, "fe-e-paz", "Titulo", "https://blog.exemplo/fe-e-paz/", true, AGORA, AGORA);
		posts.upsert(8, "esperanca", "Outro", "https://blog.exemplo/esperanca/", true, AGORA, AGORA);
		autora = jdbc.queryForObject("insert into usuario (email, email_canonico, nome_completo, papel, ativo, criado_em) values (?, ?, 'Ana Maria de Souza', 'CLIENTE', true, now()) returning id",
				Long.class, EMAIL_AUTORA, EMAIL_AUTORA);
	}

	void comentario(long post, String texto, boolean suspeito, Duration idade) {
		jdbc.update("insert into blog_comentario (post_id, usuario_id, conteudo, suspeito, criado_em) values (?, ?, ?, ?, ?)",
				post, autora, texto, suspeito, OffsetDateTime.ofInstant(AGORA.minus(idade), ZoneOffset.UTC));
	}

	@Test
	void nomeECronDaTarefa() {
		assertThat(tarefa.getName()).isEqualTo("alerta-comentarios");
		assertThat(tarefa.agenda().getInitialExecutionTime(Instant.parse("2026-11-05T15:00:00Z"))).isEqualTo(Instant.parse("2026-11-06T11:00:00Z")); // 08:00 em SP
	}

	@Test
	void assuntoELinhasComSuspeitosPrimeiroECorteEmCem() {
		comentario(7, "normal e mais novo", false, Duration.ofHours(1));
		comentario(8, "suspeito antigo", true, Duration.ofHours(10));
		comentario(7, "😀".repeat(101), false, Duration.ofHours(2));
		assertThat(tarefa.executar(false)).isEqualTo("resumo enviado: 3 comentário(s), 1 suspeito(s)");

		assertThat(caixa.enviados()).hasSize(1);
		var e = caixa.enviados().get(0).email();
		assertThat(e.para()).isEqualTo(props.emailAdmin());
		assertThat(e.assunto()).isEqualTo("Resumo de comentários do blog (3 novos, 1 suspeitos)");
		assertThat(e.texto().lines().filter(l -> l.startsWith("- "))).containsExactly(
				"- /blog/esperanca | Ana S. [SUSPEITO]: suspeito antigo",
				"- /blog/fe-e-paz | Ana S.: normal e mais novo",
				"- /blog/fe-e-paz | Ana S.: " + "😀".repeat(100) + "…");
		assertThat(e.texto()).contains("Modere em: " + props.frontendUrl().replaceAll("/+$", "") + "/admin/blog/comentarios");
		assertThat(e.html()).contains("/admin/blog/comentarios");
	}

	@Test
	void cemCaracteresExatosNaoGanhamReticencias() {
		comentario(7, "a".repeat(100), false, Duration.ofHours(1));
		tarefa.executar(false);
		assertThat(caixa.enviados().get(0).email().texto()).contains(": " + "a".repeat(100) + "\n").doesNotContain("…");
	}

	@Test
	void nenhumEmailDeQuemComentouNoCorpoNemNoLog(CapturedOutput log) {
		comentario(7, "oi!!", true, Duration.ofHours(1));
		tarefa.executar(false);
		var e = caixa.enviados().get(0).email();
		assertThat(e.texto() + e.html() + e.assunto()).doesNotContain(EMAIL_AUTORA).doesNotContain("@exemplo.com");
		assertThat(log.getAll()).doesNotContain(EMAIL_AUTORA);
	}

	@Test
	void conteudoEEscapadoNoHtml() {
		comentario(7, "<script>alert(1)</script> & cia", false, Duration.ofHours(1));
		tarefa.executar(false);
		var e = caixa.enviados().get(0).email();
		assertThat(e.html()).contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp; cia").doesNotContain("<script>");
		assertThat(e.texto()).contains("<script>alert(1)</script> & cia");
	}

	@Test
	void janelaDe24HNaBorda() {
		comentario(7, "dentro", false, Duration.ofHours(24).minusSeconds(1));
		comentario(7, "borda exata", false, Duration.ofHours(24));
		comentario(7, "fora", false, Duration.ofHours(24).plusSeconds(1));
		tarefa.executar(false);
		var texto = caixa.enviados().get(0).email().texto();
		assertThat(texto).contains(": dentro").doesNotContain("borda exata").doesNotContain(": fora");
		assertThat(caixa.enviados().get(0).email().assunto()).contains("(1 novos, 0 suspeitos)");
	}

	@Test
	void zeroComentarioNaoEnvia() {
		comentario(7, "de ontem", false, Duration.ofHours(30));
		tarefa.executar(false);
		assertThat(caixa.enviados()).isEmpty();
	}

	@Test
	void dryRunNaoEnviaSoLogaAsContagens(CapturedOutput log) {
		comentario(7, "uns", true, Duration.ofHours(1));
		comentario(7, "dois", false, Duration.ofHours(1));
		assertThat(tarefa.executar(true)).isEqualTo("2 comentário(s), 1 suspeito(s), nada enviado (simulação)");
		assertThat(caixa.enviados()).isEmpty();
		assertThat(log.getAll()).contains("alerta-comentarios (dry-run): 2 comentario(s), 1 suspeito(s), nada enviado");
	}

	@Test
	void aChaveDoDiaEmSaoPauloEEstavelEMudaNoDiaSeguinte() {
		comentario(7, "uns", false, Duration.ofHours(1));
		tarefa.executar(false);
		tarefa.executar(false); // a Resend descarta o 2o pela mesma chave
		assertThat(caixa.enviados()).extracting(CaixaDeSaidaEmMemoria.Enviado::chave).containsExactly("alerta-comentarios/2026-11-12", "alerta-comentarios/2026-11-12");

		var amanha = new AlertaDeComentarios(jdbcClient, conta, modelos, caixa, props, Clock.fixed(AGORA.plus(Duration.ofHours(24)), ZoneOffset.UTC));
		jdbc.update("update blog_comentario set criado_em = criado_em + interval '24 hours'");
		amanha.executar(false);
		assertThat(caixa.enviados().get(2).chave()).isEqualTo("alerta-comentarios/2026-11-13");
	}

	@Test
	void chaveUsaODiaDeSaoPauloEmVezDeUtc() {
		var meiaNoiteSp = Instant.parse("2026-11-13T02:00:00Z"); // 23:00 de 12/11 em SP, 02:00 de 13/11 em UTC
		var t = new AlertaDeComentarios(jdbcClient, conta, modelos, caixa, props, Clock.fixed(meiaNoiteSp, ZoneOffset.UTC));
		jdbc.update("insert into blog_comentario (post_id, usuario_id, conteudo, suspeito, criado_em) values (7, ?, 'xxx', false, ?)", autora,
				OffsetDateTime.ofInstant(meiaNoiteSp.minusSeconds(60), ZoneOffset.UTC));
		t.executar(false);
		assertThat(caixa.enviados().get(0).chave()).isEqualTo("alerta-comentarios/2026-11-12");
	}
}
