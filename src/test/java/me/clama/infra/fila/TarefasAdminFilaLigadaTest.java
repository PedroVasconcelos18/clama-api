package me.clama.infra.fila;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import java.util.UUID;
import me.clama.BancoDeTeste;
import me.clama.conta.interno.Papel;
import me.clama.conta.interno.UsuarioRepository;
import me.clama.conta.web.Navegador;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/** "Rodar agora" de ponta a ponta, com o scheduler ligado: o job e pego, a tarefa roda e o historico mostra o resumo. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import({ BancoDeTeste.class, TarefasAdminTest.Tarefas.class })
@ActiveProfiles("test")
@FilaLigada
class TarefasAdminFilaLigadaTest {

	@LocalServerPort int porta;
	@Autowired JdbcTemplate jdbc;
	@Autowired UsuarioRepository usuarios;
	@Autowired PasswordEncoder encoder;

	@AfterEach
	void limpar() {
		jdbc.update("delete from tarefa_execucao where tarefa like 'tarefa-teste-%'");
		jdbc.update("delete from auditoria where entidade = 'tarefa'");
		jdbc.update("delete from usuario where email like 'tarefas-%@exemplo.com'");
	}

	@Test
	void rodarAgoraExecutaPeloJobEGravaOHistorico() throws Exception {
		var email = "tarefas-" + UUID.randomUUID() + "@exemplo.com";
		Navegador.usuario(usuarios, encoder, email, "senha-forte-123", Papel.ADMIN);
		var admin = new Navegador(porta);
		assertThat(admin.login(email, "senha-forte-123").statusCode()).isEqualTo(200);

		assertThat(admin.post("/api/admin/tarefas/" + TarefasAdminTest.OK + "/execucoes", "{}").statusCode()).isEqualTo(202);
		await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(() -> assertThat(
				jdbc.queryForObject("select count(*) from tarefa_execucao where tarefa = ?", Integer.class, TarefasAdminTest.OK)).isEqualTo(1));
		var e = jdbc.queryForMap("select simulacao, manual, ok, resumo, autor_id from tarefa_execucao where tarefa = ?", TarefasAdminTest.OK);
		assertThat(e).containsEntry("simulacao", true).containsEntry("manual", true).containsEntry("ok", true)
				.containsEntry("resumo", "fez 3 coisas (simulacao)");
		assertThat(e.get("autor_id")).isNotNull();
	}
}
