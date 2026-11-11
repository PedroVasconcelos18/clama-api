package me.clama.oracao.interno;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Instant;
import java.util.UUID;
import me.clama.BancoDeTeste;
import me.clama.infra.cripto.HmacDeIdentificador;
import me.clama.pedido.PedidoApi;
import me.clama.pedido.interno.Pedido;
import me.clama.pedido.interno.PedidoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** D14: uma linha em {@code oracao_geracao} por chamada bem-sucedida, na transacao que grava a oracao. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class JobGerarOracaoTest {

	static final WireMockServer CLAUDE = new WireMockServer(WireMockConfiguration.options().dynamicPort());
	static final String OK = """
			{"id": "msg_1", "type": "message", "role": "assistant", "model": "claude-sonnet-4-6",
			 "content": [{"type": "text", "text": "Senhor, acolhe a Maria.\\n\\nAmém."}], "stop_reason": "end_turn",
			 "stop_sequence": null, "usage": {"input_tokens": 812, "output_tokens": 164,
			 "cache_creation_input_tokens": 300, "cache_read_input_tokens": 790}}""";

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
	@Autowired JobGerarOracao gerar;
	@MockitoSpyBean PedidoApi api;

	@BeforeEach
	@AfterEach
	void limpar() {
		CLAUDE.resetAll();
		jdbc.update(BancoDeTeste.LIMPAR_FILA);
		jdbc.update("delete from oracao_geracao");
		jdbc.update("delete from pedido");
		jdbc.update("delete from plano where tipo = 'COMUM'");
	}

	static void claude(int status, String corpo) {
		CLAUDE.stubFor(post("/v1/messages").willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(corpo)));
	}

	/** Pedido pronto para gerar (PAGO), sem passar pelo evento que enfileira. */
	UUID pedidoPago() {
		var plano = jdbc.queryForObject("""
				insert into plano (nome, valor_centavos, complexidade, tipo, ordem) values (?, 3000, 'SIMPLES', 'COMUM', 1)
				returning id""", Long.class, "Oração Simples " + UUID.randomUUID());
		var agora = Instant.now();
		var d = new Pedido.Dados(null, plano, null, 3000, "Maria Souza", "maria@exemplo.com", "52998224725", "11999990000",
				(short) 40, "feminino", "Pela família", agora, null, "1");
		var p = Pedido.novo(d, hmac, agora);
		p.confirmarPagamento(agora);
		return pedidos.saveAndFlush(p).getId();
	}

	int linhas() {
		return jdbc.queryForObject("select count(*) from oracao_geracao", Integer.class);
	}

	@Test
	void umaLinhaPorGeracaoComOsQuatroTokens() {
		claude(200, OK);
		var id = pedidoPago();

		gerar.executar(id, 0);

		assertThat(linhas()).isEqualTo(1);
		var l = jdbc.queryForMap("select * from oracao_geracao");
		assertThat(l).containsEntry("pedido_id", id).containsEntry("modelo", "claude-sonnet-4-6")
				.containsEntry("tokens_entrada", 812).containsEntry("tokens_saida", 164)
				.containsEntry("tokens_cache_escrita", 300).containsEntry("tokens_cache_leitura", 790);
		assertThat((Integer) l.get("duracao_ms")).isNotNegative();
		assertThat(jdbc.queryForObject("select status from pedido where id = ?", String.class, id)).isEqualTo("ORACAO_GERADA");
	}

	@Test
	void retentativaQueFalhaNaoGravaEACertaGravaUma() {
		var id = pedidoPago();
		claude(503, "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"x\"}}");

		assertThatThrownBy(() -> gerar.executar(id, 0)).isInstanceOf(ClienteClaude.Indisponivel.class);
		assertThat(linhas()).isZero();

		CLAUDE.resetAll();
		claude(200, OK);
		gerar.executar(id, 1);
		assertThat(linhas()).isEqualTo(1);
	}

	@Test
	void usoGravadoSoSeAOracaoFoiRegistrada() {
		claude(200, OK);
		var id = pedidoPago();
		doThrow(new IllegalStateException("banco caiu")).when(api).registrarOracao(any(), any());

		assertThatThrownBy(() -> gerar.executar(id, 0)).isInstanceOf(IllegalStateException.class);

		assertThat(linhas()).isZero(); // a mesma transacao: sem oracao, sem linha
	}

	@Test
	void oracaoJaGeradaNaoChamaAClaudeNemGravaDeNovo() {
		claude(200, OK);
		var id = pedidoPago();
		gerar.executar(id, 0);

		gerar.executar(id, 0);

		assertThat(linhas()).isEqualTo(1);
		assertThat(CLAUDE.findAll(postRequestedFor(urlEqualTo("/v1/messages")))).hasSize(1);
	}
}
