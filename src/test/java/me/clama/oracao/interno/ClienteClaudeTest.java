package me.clama.oracao.interno;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** O SDK oficial contra um WireMock: o que sai na rede, a retentativa nossa e como cada falha volta. */
@WireMockTest
class ClienteClaudeTest {

	static final String OK = """
			{"id": "msg_1", "type": "message", "role": "assistant", "model": "claude-sonnet-4-6",
			 "content": [{"type": "text", "text": "Senhor, acolhe a Maria.\\n\\nAmém."}], "stop_reason": "end_turn",
			 "stop_sequence": null, "usage": {"input_tokens": 812, "output_tokens": 164,
			 "cache_creation_input_tokens": 0, "cache_read_input_tokens": 790}}""";
	static final String SOBRECARGA = """
			{"type": "error", "error": {"type": "overloaded_error", "message": "Overloaded"}}""";
	static final String SEM_CREDITOS = """
			{"type": "error", "error": {"type": "invalid_request_error", "message":
			 "Your credit balance is too low to access the Anthropic API. Please go to Plans & Billing to upgrade or purchase credits."}}""";

	final List<Duration> esperas = new ArrayList<>();

	ClienteClaude cliente(String url, String chave, Duration timeout) {
		return new ClienteClaude(new OracaoProps(chave, url, "claude-sonnet-4-6", 1500, timeout), esperas::add);
	}

	ClienteClaude cliente(WireMockRuntimeInfo wm) {
		return cliente(wm.getHttpBaseUrl(), "sk-ant-teste", Duration.ofSeconds(5));
	}

	static void responde(int status, String corpo) {
		stubFor(post("/v1/messages").willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(corpo)));
	}

	/** Respostas em sequencia: a ultima se repete. */
	static void sequencia(int[] status, String[] corpos) {
		for (int i = 0; i < status.length; i++) {
			stubFor(post("/v1/messages").inScenario("s").whenScenarioStateIs(i == 0 ? Scenario.STARTED : "r" + i)
					.willReturn(aResponse().withStatus(status[i]).withHeader("Content-Type", "application/json").withBody(corpos[i]))
					.willSetStateTo(i == status.length - 1 ? "r" + i : "r" + (i + 1)));
		}
	}

	@Test
	void corpoComModeloTokensSystemEmCacheEMensagem(WireMockRuntimeInfo wm) {
		responde(200, OK);

		assertThat(cliente(wm).gerar("Você é o Clama.", "Pedido: paz")).isEqualTo("Senhor, acolhe a Maria.\n\nAmém.");

		verify(1, postRequestedFor(urlEqualTo("/v1/messages")).withRequestBody(equalToJson("""
				{"model": "claude-sonnet-4-6", "max_tokens": 1500,
				 "system": [{"type": "text", "text": "Você é o Clama.", "cache_control": {"type": "ephemeral"}}],
				 "messages": [{"role": "user", "content": "Pedido: paz"}]}""", true, false))
				.withHeader("anthropic-version", matching(".+")).withHeader("x-api-key", equalTo("sk-ant-teste"))
				.withoutHeader("Authorization"));
		assertThat(esperas).isEmpty();
	}

	@Test
	void sobrecargaRetentaCom1e2Segundos(WireMockRuntimeInfo wm) {
		sequencia(new int[] { 529, 529, 200 }, new String[] { SOBRECARGA, SOBRECARGA, OK });

		assertThat(cliente(wm).gerar("s", "m")).startsWith("Senhor");

		verify(3, postRequestedFor(urlEqualTo("/v1/messages")));
		assertThat(esperas).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
	}

	@Test
	void sobrecargaEsgotadaViraIndisponivel(WireMockRuntimeInfo wm) {
		responde(529, SOBRECARGA);

		assertThatThrownBy(() -> cliente(wm).gerar("s", "m")).isExactlyInstanceOf(ClienteClaude.Indisponivel.class);

		verify(3, postRequestedFor(urlEqualTo("/v1/messages")));
		assertThat(esperas).hasSize(2);
	}

	@Test
	void semCreditosNaoRetenta(WireMockRuntimeInfo wm) {
		responde(400, SEM_CREDITOS);

		assertThatThrownBy(() -> cliente(wm).gerar("s", "m")).isExactlyInstanceOf(ClienteClaude.CreditosEsgotados.class);

		verify(1, postRequestedFor(urlEqualTo("/v1/messages")));
		assertThat(esperas).isEmpty();
	}

	/** 400 comum e resposta ruim (502 na previa); 401, 429 e 500 sao indisponibilidade (503). Nenhum retenta. */
	@Test
	void outrosStatusFalhamNaHora(WireMockRuntimeInfo wm) {
		var c = cliente(wm);
		responde(400, """
				{"type": "error", "error": {"type": "invalid_request_error", "message": "messages: field required"}}""");
		assertThatThrownBy(() -> c.gerar("s", "m")).isExactlyInstanceOf(ClienteClaude.Falha.class);
		for (int status : new int[] { 401, 429, 500 }) {
			responde(status, """
					{"type": "error", "error": {"type": "x", "message": "falhou"}}""");
			assertThatThrownBy(() -> c.gerar("s", "m")).as("%d", status).isExactlyInstanceOf(ClienteClaude.Indisponivel.class);
		}
		verify(4, postRequestedFor(urlEqualTo("/v1/messages")));
		assertThat(esperas).isEmpty();
	}

	@Test
	void primeiroBlocoNaoTextoEFalha(WireMockRuntimeInfo wm) {
		responde(200, """
				{"id": "msg_1", "type": "message", "role": "assistant", "model": "claude-sonnet-4-6",
				 "content": [{"type": "tool_use", "id": "t1", "name": "x", "input": {}}], "stop_reason": "tool_use",
				 "stop_sequence": null, "usage": {"input_tokens": 1, "output_tokens": 1}}""");
		assertThatThrownBy(() -> cliente(wm).gerar("s", "m")).isExactlyInstanceOf(ClienteClaude.Falha.class);

		responde(200, """
				{"id": "msg_1", "type": "message", "role": "assistant", "model": "claude-sonnet-4-6", "content": [],
				 "stop_reason": "end_turn", "stop_sequence": null, "usage": {"input_tokens": 1, "output_tokens": 0}}""");
		assertThatThrownBy(() -> cliente(wm).gerar("s", "m")).isExactlyInstanceOf(ClienteClaude.Falha.class);
		assertThat(esperas).isEmpty();
	}

	@Test
	void timeoutRetenta(WireMockRuntimeInfo wm) {
		stubFor(post("/v1/messages").willReturn(okJson(OK).withFixedDelay(1000)));

		assertThatThrownBy(() -> cliente(wm.getHttpBaseUrl(), "sk-ant-teste", Duration.ofMillis(200)).gerar("s", "m"))
				.isExactlyInstanceOf(ClienteClaude.Indisponivel.class);

		verify(3, postRequestedFor(urlEqualTo("/v1/messages")));
		assertThat(esperas).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
	}

	@Test
	void conexaoRecusadaRetenta() throws Exception {
		int porta;
		try (var s = new ServerSocket(0)) {
			porta = s.getLocalPort();
		}
		assertThatThrownBy(() -> cliente("http://127.0.0.1:" + porta, "sk-ant-teste", Duration.ofSeconds(2)).gerar("s", "m"))
				.isExactlyInstanceOf(ClienteClaude.Indisponivel.class);
		assertThat(esperas).hasSize(2);
	}

	/** Oracao cortada no max_tokens: devolve o texto sem retentar e avisa em WARN (a T4 decide sobre alerta). */
	@Test
	void maxTokensAvisaSemRetentar(WireMockRuntimeInfo wm) {
		responde(200, OK.replace("end_turn", "max_tokens"));
		var log = (Logger) LoggerFactory.getLogger(ClienteClaude.class);
		var eventos = new ListAppender<ILoggingEvent>();
		eventos.start();
		log.addAppender(eventos);
		try {
			assertThat(cliente(wm).gerar("s", "m")).startsWith("Senhor");
		} finally {
			log.detachAppender(eventos);
		}
		verify(1, postRequestedFor(urlEqualTo("/v1/messages")));
		assertThat(eventos.list).anyMatch(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("max_tokens"));
	}

	/** A previa e uma chamada so: o admin espera na tela. */
	@Test
	void previaNaoRetenta(WireMockRuntimeInfo wm) {
		responde(529, SOBRECARGA);

		assertThatThrownBy(() -> cliente(wm).previa("s", "m")).isExactlyInstanceOf(ClienteClaude.Indisponivel.class);

		verify(1, postRequestedFor(urlEqualTo("/v1/messages")));
		assertThat(esperas).isEmpty();
	}

	/** Sem chave o boot sobe; a geracao falha com erro claro e nada vai para a rede. */
	@Test
	void semChaveFalhaSemChamar(WireMockRuntimeInfo wm) {
		var c = cliente(wm.getHttpBaseUrl(), " ", Duration.ofSeconds(1));
		assertThatThrownBy(() -> c.gerar("s", "m")).isExactlyInstanceOf(ClienteClaude.Indisponivel.class)
				.hasMessageContaining("ANTHROPIC_API_KEY");
		verify(0, postRequestedFor(anyUrl()));
	}

	/** Documentos ativos: blocos document por file_id antes do texto, um cache_control so (no ultimo), o do system fica. Sem header beta. */
	@Test
	void documentosViramBlocosComUmCacheControlNoUltimo(WireMockRuntimeInfo wm) {
		responde(200, OK);

		cliente(wm).gerar("Sistema.", "Pedido: paz", List.of("file_a", "file_b"));

		verify(1, postRequestedFor(urlEqualTo("/v1/messages")).withRequestBody(equalToJson("""
				{"system": [{"type": "text", "text": "Sistema.", "cache_control": {"type": "ephemeral"}}],
				 "messages": [{"role": "user", "content": [
				   {"type": "document", "source": {"type": "file", "file_id": "file_a"}},
				   {"type": "document", "source": {"type": "file", "file_id": "file_b"}, "cache_control": {"type": "ephemeral"}},
				   {"type": "text", "text": "Pedido: paz"}]}]}""", false, true)).withoutHeader("anthropic-beta"));
		var corpo = findAll(postRequestedFor(urlEqualTo("/v1/messages"))).get(0).getBodyAsString();
		assertThat(corpo.split("cache_control", -1)).hasSize(3); // system + ultimo documento
	}

	@Test
	void umDocumentoSoTemOCacheControlDele(WireMockRuntimeInfo wm) {
		responde(200, OK);
		cliente(wm).gerar("s", "m", List.of("file_a"));
		verify(postRequestedFor(urlEqualTo("/v1/messages")).withRequestBody(
				matchingJsonPath("$.messages[0].content[0].cache_control.type", equalTo("ephemeral"))));
	}

	@Test
	void enviarArquivoMandaMultipartComNomeETipo(WireMockRuntimeInfo wm) throws Exception {
		stubFor(post("/v1/files").willReturn(okJson("""
				{"id": "file_011", "type": "file", "filename": "salmos.txt", "mime_type": "text/plain", "size_bytes": 5,
				 "created_at": "2026-11-12T10:00:00Z", "downloadable": false}""")));
		var f = java.nio.file.Files.createTempFile("doc", ".bin");
		java.nio.file.Files.writeString(f, "Salmo");
		try {
			assertThat(cliente(wm).enviarArquivo(f, "salmos.txt", "text/plain")).isEqualTo("file_011");
		} finally {
			java.nio.file.Files.delete(f);
		}
		verify(1, postRequestedFor(urlEqualTo("/v1/files")).withHeader("x-api-key", equalTo("sk-ant-teste"))
				.withHeader("Content-Type", containing("multipart/form-data"))
				.withRequestBody(containing("filename=\"salmos.txt\"")).withRequestBody(containing("Content-Type: text/plain"))
				.withRequestBody(containing("Salmo")));
	}

	@Test
	void apagarArquivoToleraOs404EFalhaNosOutros(WireMockRuntimeInfo wm) {
		var c = cliente(wm);
		stubFor(delete("/v1/files/file_ok").willReturn(okJson("{\"id\": \"file_ok\", \"type\": \"file_deleted\"}")));
		c.apagarArquivo("file_ok");
		stubFor(delete("/v1/files/file_x").willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/json")
				.withBody("{\"type\": \"error\", \"error\": {\"type\": \"not_found_error\", \"message\": \"File not found\"}}")));
		c.apagarArquivo("file_x");
		stubFor(delete("/v1/files/file_y").willReturn(aResponse().withStatus(500).withHeader("Content-Type", "application/json")
				.withBody("{\"type\": \"error\", \"error\": {\"type\": \"api_error\", \"message\": \"x\"}}")));
		assertThatThrownBy(() -> c.apagarArquivo("file_y")).isInstanceOf(ClienteClaude.Falha.class);
	}

	@Test
	void arquivoExisteDistingue404DeFalha(WireMockRuntimeInfo wm) {
		var c = cliente(wm);
		stubFor(get("/v1/files/file_ok").willReturn(okJson("""
				{"id": "file_ok", "type": "file", "filename": "a.pdf", "mime_type": "application/pdf", "size_bytes": 5,
				 "created_at": "2026-11-12T10:00:00Z", "downloadable": false}""")));
		assertThat(c.arquivoExiste("file_ok")).isTrue();
		stubFor(get("/v1/files/file_x").willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/json")
				.withBody("{\"type\": \"error\", \"error\": {\"type\": \"not_found_error\", \"message\": \"nao\"}}")));
		assertThat(c.arquivoExiste("file_x")).isFalse();
		stubFor(get("/v1/files/file_y").willReturn(aResponse().withStatus(500).withHeader("Content-Type", "application/json")
				.withBody("{\"type\": \"error\", \"error\": {\"type\": \"api_error\", \"message\": \"x\"}}")));
		assertThatThrownBy(() -> c.arquivoExiste("file_y")).isInstanceOf(ClienteClaude.Indisponivel.class);
	}
}
