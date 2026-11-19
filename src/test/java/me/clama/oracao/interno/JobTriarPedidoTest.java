package me.clama.oracao.interno;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import me.clama.BancoDeTeste;
import me.clama.conta.interno.Papel;
import me.clama.conta.interno.UsuarioRepository;
import me.clama.conta.web.Navegador;
import me.clama.infra.cripto.HmacDeIdentificador;
import me.clama.pedido.NovoPedido;
import me.clama.pedido.PedidoApi;
import me.clama.pedido.interno.CriacaoDePedido;
import me.clama.pedido.interno.Pedido;
import me.clama.pedido.interno.PedidoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** {@code triar-pedido/<pedido>}: grava {@code pedido.risco} e, com risco, alerta o admin; falhar nunca mexe no pedido. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class JobTriarPedidoTest {

	static final WireMockServer CLAUDE = new WireMockServer(WireMockConfiguration.options().dynamicPort());

	static {
		CLAUDE.start();
	}

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		r.add("clama.oracao.anthropic-url", CLAUDE::baseUrl);
		r.add("clama.oracao.anthropic-api-key", () -> "sk-ant-teste");
	}

	@Autowired JdbcTemplate jdbc;
	@Autowired PedidoRepository pedidos;
	@Autowired HmacDeIdentificador hmac;
	@Autowired PedidoApi api;
	@Autowired CriacaoDePedido criacao;
	@Autowired UsuarioRepository usuarios;
	@Autowired PasswordEncoder encoder;
	@Autowired JobTriarPedido triar;
	@Autowired JobAlertaAdmin alerta;

	@BeforeEach
	@AfterEach
	void limpar() {
		CLAUDE.resetAll();
		jdbc.update(BancoDeTeste.LIMPAR_FILA);
		jdbc.update("delete from pedido");
		jdbc.update("delete from usuario where email like 'jtp-%'");
		jdbc.update("delete from plano where tipo = 'COMUM'");
		jdbc.update("delete from instituicao where nome = 'Lar da Triagem'");
	}

	static void responde(String palavra) {
		CLAUDE.stubFor(post("/v1/messages").willReturn(okJson("""
				{"id": "msg_1", "type": "message", "role": "assistant", "model": "claude-sonnet-4-6",
				 "content": [{"type": "text", "text": "%s"}], "stop_reason": "end_turn",
				 "stop_sequence": null, "usage": {"input_tokens": 300, "output_tokens": 3}}""".formatted(palavra))));
	}

	Long plano() {
		return jdbc.queryForObject("""
				insert into plano (nome, valor_centavos, complexidade, tipo, ordem) values (?, 1000, 'SIMPLES', 'COMUM', 1)
				returning id""", Long.class, "Oração " + UUID.randomUUID());
	}

	/** Pedido AGUARDANDO_PAGAMENTO, sem passar pela criacao (que enfileiraria a triagem). */
	UUID pedido(String texto) {
		var agora = Instant.now();
		var d = new Pedido.Dados(null, plano(), null, 3000, "Maria Souza", "maria@exemplo.com", "52998224725", null,
				(short) 40, "feminino", texto, agora, null, "1", null);
		return pedidos.saveAndFlush(Pedido.novo(d, hmac, agora)).getId();
	}

	Boolean risco(UUID id) {
		return jdbc.queryForObject("select risco from pedido where id = ?", Boolean.class, id);
	}

	String status(UUID id) {
		return jdbc.queryForObject("select status from pedido where id = ?", String.class, id);
	}

	List<String> jobs(String tarefa) {
		return jdbc.queryForList("select task_instance from scheduled_tasks where task_name = ?", String.class, tarefa);
	}

	@Test
	void nomeERetentativas() {
		assertThat(triar.getName()).isEqualTo("triar-pedido");
		assertThat(triar.instancia(UUID.fromString("00000000-0000-0000-0000-000000000001"))).isEqualTo("triar-pedido/00000000-0000-0000-0000-000000000001");
	}

	@Test
	void riscoMarcaOPedidoEAlerta() {
		responde("RISCO");
		var id = pedido("Não aguento mais viver");

		triar.executar(id, 0);

		assertThat(risco(id)).isTrue();
		assertThat(jobs("alerta-admin")).containsExactly("alerta-admin/" + id + "/risco");
		assertThat(status(id)).isEqualTo("AGUARDANDO_PAGAMENTO");
	}

	@Test
	void semRiscoMarcaFalseSemAlerta() {
		responde("SEM_RISCO");
		var id = pedido("Pela minha família");

		triar.executar(id, 0);

		assertThat(risco(id)).isFalse();
		assertThat(jobs("alerta-admin")).isEmpty();
	}

	@Test
	void pedidoSemTextoNaoChamaAClaude() {
		responde("RISCO");
		var id = pedido(null);

		triar.executar(id, 0);

		assertThat(risco(id)).isFalse();
		assertThat(CLAUDE.findAll(postRequestedFor(urlEqualTo("/v1/messages")))).isEmpty();
		assertThat(jobs("alerta-admin")).isEmpty();
	}

	@Test
	void jobQueDesisteNaoMudaOPedidoEAlertaOAdmin() {
		CLAUDE.stubFor(post("/v1/messages").willReturn(aResponse().withStatus(500).withHeader("Content-Type", "application/json")
				.withBody("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"x\"}}")));
		var id = pedido("Pela minha família");
		api.concederGratuidade(id); // PAGO, com gerar-oracao na fila
		var antes = jdbc.queryForMap("select status, atualizado_em, risco from pedido where id = ?", id);

		for (int tentativa = 0; tentativa <= 3; tentativa++) { // a primeira e as 3 retentativas
			int t = tentativa;
			assertThatThrownBy(() -> triar.executar(id, t)).isInstanceOf(IllegalStateException.class);
		}
		triar.aoDesistir(id, new IllegalStateException("triagem sem resposta valida"));

		assertThat(jdbc.queryForMap("select status, atualizado_em, risco from pedido where id = ?", id)).isEqualTo(antes);
		assertThat(risco(id)).isNull();
		assertThat(jobs("gerar-oracao")).containsExactly("gerar-oracao/" + id);
		assertThat(jobs("alerta-admin")).containsExactly("alerta-admin/" + id + "/triagem_falhou");
		assertThat(alerta.detalhe("triagem_falhou")).isEqualTo(
				"A triagem de risco deste pedido falhou em todas as tentativas. Confira o texto do pedido no admin.");
	}

	@Test
	void pedidoQueSumiuNaoFalha() {
		triar.executar(UUID.randomUUID(), 0);
		assertThat(CLAUDE.findAll(postRequestedFor(urlEqualTo("/v1/messages")))).isEmpty();
	}

	/** As 4 rotas de criacao (pago, doacao, gratuito, freemium) passam pela {@link CriacaoDePedido}. */
	@Test
	void criarPedidoEnfileiraATriagem() {
		jdbc.update("""
				insert into plano (nome, descricao, valor_centavos, complexidade, tipo, ordem)
				values ('Gratuito', 'Sua primeira oração', 0, 'SIMPLES', 'GRATUITO', 1000)
				on conflict (tipo) where tipo <> 'COMUM' do nothing""");
		plano();
		var inst = jdbc.queryForObject("insert into instituicao (nome, ativo) values ('Lar da Triagem', true) returning id", Long.class);
		var conta = Navegador.usuario(usuarios, encoder, "jtp-" + Navegador.emailUnico(), "senha-forte-123", Papel.CLIENTE).getId();
		var n = new NovoPedido("Maria Souza", "maria@exemplo.com", "529.982.247-25", null, (short) 40, "feminino", "Pela minha família",
				null, inst, 3000L, true, null, "familia");

		var ids = List.of(criacao.criarPago(conta, n, "203.0.113.1").getId(), criacao.criarDoacao(n, "203.0.113.1").getId(),
				criacao.criarGratuito(conta, n, "203.0.113.1").getId(), criacao.criarFreemium(n, "203.0.113.1").getId());

		assertThat(jobs("triar-pedido")).containsExactlyInAnyOrderElementsOf(ids.stream().map(id -> "triar-pedido/" + id).toList());
	}
}
