package me.clama.oracao.interno;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.Optional;
import me.clama.BancoDeTeste;
import me.clama.conta.web.Navegador;
import me.clama.infra.seguranca.LimiteDeTaxa;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Triagem de crise contra a Claude num WireMock: uma palavra vira booleano; qualquer outra coisa ou falha vira vazio. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class TriagemTest {

	static final WireMockServer CLAUDE = new WireMockServer(WireMockConfiguration.options().dynamicPort());
	static final String TEXTO = "Não aguento mais, quero sumir de vez";

	static {
		CLAUDE.start();
	}

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		r.add("clama.oracao.anthropic-url", CLAUDE::baseUrl);
		r.add("clama.oracao.anthropic-api-key", () -> "sk-ant-teste");
		r.add("clama.oracao.retentativas-do-sdk", () -> "2"); // como em prod: a triagem nao pode herdar as retentativas do SDK
		r.add("clama.triagem.timeout", () -> "1s"); // o da oracao fica no padrao (30 s)
	}

	@LocalServerPort int porta;
	@Autowired Triagem triagem;
	@Autowired LimiteDeTaxa limite;

	@BeforeEach
	@AfterEach
	void limpar() {
		CLAUDE.resetAll();
		limite.limpar();
	}

	static String resposta(String texto) {
		return """
				{"id": "msg_1", "type": "message", "role": "assistant", "model": "claude-sonnet-4-6",
				 "content": [{"type": "text", "text": "%s"}], "stop_reason": "end_turn",
				 "stop_sequence": null, "usage": {"input_tokens": 300, "output_tokens": 3}}""".formatted(texto);
	}

	static void claude(ResponseDefinitionBuilder r) {
		CLAUDE.stubFor(post("/v1/messages").willReturn(r));
	}

	static void responde(String texto) {
		claude(okJson(resposta(texto)));
	}

	@Test
	void riscoViraVerdadeiro() {
		responde("RISCO");
		assertThat(triagem.avaliar(TEXTO)).isEqualTo(Optional.of(true));
	}

	@Test
	void semRiscoViraFalso() {
		responde("SEM_RISCO");
		assertThat(triagem.avaliar(TEXTO)).isEqualTo(Optional.of(false));
	}

	@Test
	void respostaAparadaESemCaixa() {
		responde(" risco\\n");
		assertThat(triagem.avaliar(TEXTO)).isEqualTo(Optional.of(true));
	}

	@Test
	void respostaForaDoFormatoEVaziaSemLogarOTexto() {
		var log = (Logger) LoggerFactory.getLogger("me.clama");
		var appender = new ListAppender<ILoggingEvent>();
		appender.start();
		log.addAppender(appender);
		try {
			responde("Talvez");
			assertThat(triagem.avaliar(TEXTO)).isEmpty();
		} finally {
			log.detachAppender(appender);
		}
		assertThat(appender.list).isNotEmpty().allSatisfy(e -> assertThat(e.getFormattedMessage())
				.doesNotContain("aguento").doesNotContain("Talvez"));
	}

	@Test
	void erro500EVazio() {
		claude(aResponse().withStatus(500).withHeader("Content-Type", "application/json")
				.withBody("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"x\"}}"));
		assertThat(triagem.avaliar(TEXTO)).isEmpty();
		CLAUDE.verify(1, postRequestedFor(urlEqualTo("/v1/messages")));
	}

	@Test
	void timeoutEVazioDentroDoPrazoDaTriagem() {
		claude(okJson(resposta("RISCO")).withFixedDelay(3000));
		var inicio = System.nanoTime();

		assertThat(triagem.avaliar(TEXTO)).isEmpty();

		assertThat(java.time.Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(java.time.Duration.ofMillis(2500));
		CLAUDE.verify(1, postRequestedFor(urlEqualTo("/v1/messages")));
	}

	@Test
	void textoEmBrancoEFalsoSemChamarAClaude() {
		assertThat(triagem.avaliar("  ")).isEqualTo(Optional.of(false));
		assertThat(triagem.avaliar(null)).isEqualTo(Optional.of(false));
		CLAUDE.verify(0, postRequestedFor(urlEqualTo("/v1/messages")));
	}

	/** O modelo da oracao (padrao de {@code clama.triagem.modelo}), 256 tokens, o system do arquivo, sem cache e sem thinking. */
	@Test
	void corpoDaChamada() {
		responde("SEM_RISCO");
		triagem.avaliar(TEXTO);
		CLAUDE.verify(1, postRequestedFor(urlEqualTo("/v1/messages"))
				.withRequestBody(matchingJsonPath("$.model", equalTo("claude-sonnet-4-6")))
				.withRequestBody(matchingJsonPath("$.max_tokens", equalTo("256")))
				.withRequestBody(matchingJsonPath("$.messages[0].content", equalTo(TEXTO)))
				.withRequestBody(containing("responde com UMA palavra"))
				.withRequestBody(notContaining("cache_control"))
				.withRequestBody(notContaining("thinking")));
	}

	@Test
	void triagemQueFalhaDevolveRiscoNulo() throws Exception {
		claude(aResponse().withStatus(500).withHeader("Content-Type", "application/json")
				.withBody("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"x\"}}"));
		var nav = new Navegador(porta);
		nav.get("/api/auth/eu");

		var res = nav.post("/api/triagem", Navegador.JSON.writeValueAsString(java.util.Map.of("texto", TEXTO)));

		assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
		assertThat(res.body()).isEqualTo("{\"risco\":null}");
	}

	@Test
	void triagemDaTelaDevolveRisco() throws Exception {
		responde("RISCO");
		var nav = new Navegador(porta);
		nav.get("/api/auth/eu");

		var res = nav.post("/api/triagem", Navegador.JSON.writeValueAsString(java.util.Map.of("texto", TEXTO)));

		assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
		assertThat(res.body()).isEqualTo("{\"risco\":true}");
	}
}
