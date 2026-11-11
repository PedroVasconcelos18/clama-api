package me.clama.blog.interno;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import me.clama.BancoDeTeste;
import me.clama.infra.fila.TarefaRecorrente;
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

/** {@code purgar-ips} (D12): 6 meses de calendario (SP) + 1 s purga, exatamente 6 meses fica, nulo nao conta, dry-run so conta. */
@SpringBootTest
@Import(BancoDeTeste.class)
@ExtendWith(OutputCaptureExtension.class)
class PurgaDeIpsTest {

	static final Instant AGORA = Instant.parse("2026-11-12T11:00:00Z");

	@Autowired JdbcTemplate jdbc;
	@Autowired JdbcClient jdbcClient;
	@Autowired PostRepository posts;

	PurgaDeIps tarefa;
	Long usuario;

	@AfterEach
	void limpar() {
		jdbc.update("delete from blog_comentario");
		jdbc.update("delete from blog_post");
		jdbc.update("delete from usuario where email like 'purga-%'");
	}

	@BeforeEach
	void preparar() {
		limpar();
		tarefa = new PurgaDeIps(jdbcClient, Clock.fixed(AGORA, ZoneOffset.UTC));
		posts.upsert(7, "fe", "Titulo", "https://blog.exemplo/fe/", true, AGORA, AGORA);
		usuario = jdbc.queryForObject("insert into usuario (email, email_canonico, nome_completo, papel, ativo, criado_em) values ('purga-a@exemplo.com', 'purga-a@exemplo.com', 'Fulana', 'CLIENTE', true, now()) returning id",
				Long.class);
	}

	long comentario(Duration idade, String ip) {
		return comentarioEm(AGORA.minus(idade), ip);
	}

	long comentarioEm(Instant quando, String ip) {
		return jdbc.queryForObject("insert into blog_comentario (post_id, usuario_id, conteudo, suspeito, ip_cifrado, criado_em) values (7, ?, 'texto', false, ?, ?) returning id",
				Long.class, usuario, ip, OffsetDateTime.ofInstant(quando, ZoneOffset.UTC));
	}

	String ip(long id) {
		return jdbc.queryForObject("select ip_cifrado from blog_comentario where id = ?", String.class, id);
	}

	@Test
	void nomeECronDaTarefa() {
		assertThat(tarefa.getName()).isEqualTo("purgar-ips");
		assertThat(tarefa.agenda().getInitialExecutionTime(Instant.parse("2026-11-05T15:00:00Z"))).isEqualTo(Instant.parse("2026-11-06T06:00:00Z")); // 03:00 em SP
	}

	@Test
	void seisMesesMaisUmSegundoPurgaEExatamenteSeisMesesFica(CapturedOutput log) {
		var seisMeses = AGORA.atZone(TarefaRecorrente.FUSO).minusMonths(6).toInstant(); // 2026-05-12T08:00-03:00
		var velho = comentarioEm(seisMeses.minusSeconds(1), "cifrado-1");
		var borda = comentarioEm(seisMeses, "cifrado-2");
		var novo = comentario(Duration.ofDays(1), "cifrado-3");
		assertThat(tarefa.executar(false)).isEqualTo("1 IP(s) apagados");
		assertThat(ip(velho)).isNull();
		assertThat(ip(borda)).isEqualTo("cifrado-2");
		assertThat(ip(novo)).isEqualTo("cifrado-3");
		assertThat(log.getAll()).contains("purgar-ips: 1 IP(s) apagados");
		assertThat(jdbc.queryForObject("select count(*) from blog_comentario", Integer.class)).isEqualTo(3); // nunca apaga o comentario
	}

	@Test
	void ipNuloNaoConta(CapturedOutput log) {
		comentario(Duration.ofDays(200), null);
		var velho = comentario(Duration.ofDays(200), "cifrado-1");
		tarefa.executar(false);
		assertThat(ip(velho)).isNull();
		assertThat(log.getAll()).contains("purgar-ips: 1 IP(s) apagados");
		tarefa.executar(false);
		assertThat(log.getAll()).contains("purgar-ips: 0 IP(s) apagados");
	}

	@Test
	void dryRunSoConta(CapturedOutput log) {
		var velho = comentario(Duration.ofDays(200), "cifrado-1");
		comentario(Duration.ofDays(200), null);
		assertThat(tarefa.executar(true)).isEqualTo("1 IP(s) seriam apagados (simulação)");
		assertThat(ip(velho)).isEqualTo("cifrado-1");
		assertThat(log.getAll()).contains("purgar-ips (dry-run): 1 IP(s) seriam apagados");
	}
}
