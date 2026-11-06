package me.clama.api.comum.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import me.clama.api.comum.retry.HttpStatusException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ResendEnviadorTest {
	static final JsonMapper JSON = JsonMapper.builder().build();
	static final String DE = "Clama <oracao@clama.me>";

	HttpServer server;
	final List<String> corpos = new CopyOnWriteArrayList<>();
	final List<String[]> headers = new CopyOnWriteArrayList<>();
	final List<Integer> respostas = new CopyOnWriteArrayList<>();
	final List<Duration> dormiu = new ArrayList<>();
	volatile String corpo409 = "";

	@BeforeEach
	void sobe() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/emails", ex -> {
			corpos.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			headers.add(new String[] {
					ex.getRequestMethod(),
					ex.getRequestHeaders().getFirst("Authorization"),
					ex.getRequestHeaders().getFirst("Content-Type"),
					ex.getRequestHeaders().getFirst("Accept"),
					ex.getRequestHeaders().getFirst("Idempotency-Key")});
			int i = corpos.size() - 1;
			int status = i < respostas.size() ? respostas.get(i) : 200;
			byte[] b = (status == 422 ? "{\"name\":\"validation_error\",\"message\":\"Invalid `to` field\"}"
					: status == 409 ? corpo409 : "{\"id\":\"abc\"}").getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders(status, b.length);
			ex.getResponseBody().write(b);
			ex.close();
		});
		server.start();
	}

	@AfterEach
	void desce() { server.stop(0); }

	ResendEnviador enviador() {
		return new ResendEnviador("re_chave", "http://127.0.0.1:" + server.getAddress().getPort() + "/", DE, JSON, dormiu::add);
	}

	static final Email EMAIL = new Email("ana@exemplo.com", "Assunto", "texto", "<p>html</p>");

	@Test
	void retornaEnviadoNoSucesso() {
		assertThat(enviador().enviar(EMAIL)).isEqualTo(ResultadoEnvio.ENVIADO);
	}

	@Test
	void conflitoInvalidIdempotentRequestEJaEnviadoSemRetentar() {
		respostas.add(409);
		corpo409 = "{\"statusCode\":409,\"name\":\"invalid_idempotent_request\",\"message\":\"x\"}";
		assertThat(enviador().enviar(EMAIL.comChave("oracao/1"))).isEqualTo(ResultadoEnvio.JA_ENVIADO);
		assertThat(corpos).hasSize(1);
		assertThat(dormiu).isEmpty();
	}

	@Test
	void conflitoInvalidIdempotentRequestPeloCampoType() {
		respostas.add(409);
		corpo409 = "{\"type\":\"invalid_idempotent_request\"}";
		assertThat(enviador().enviar(EMAIL.comChave("oracao/1"))).isEqualTo(ResultadoEnvio.JA_ENVIADO);
	}

	@Test
	void conflitoConcurrentIdempotentRequestsSegueErroRetentavel() {
		respostas.add(409);
		corpo409 = "{\"name\":\"concurrent_idempotent_requests\",\"message\":\"x\"}";
		assertThatThrownBy(() -> enviador().enviar(EMAIL.comChave("oracao/1")))
				.isInstanceOfSatisfying(HttpStatusException.class, e -> assertThat(e.status()).isEqualTo(409));
		assertThat(corpos).hasSize(1);
	}

	@Test
	void conflito409SemCorpoReconhecivelEErro() {
		respostas.add(409);
		corpo409 = "nao e json";
		assertThatThrownBy(() -> enviador().enviar(EMAIL)).isInstanceOf(HttpStatusException.class);
	}

	@Test
	void enviaPayloadDoAnymail() throws Exception {
		enviador().enviar(EMAIL);
		assertThat(corpos).hasSize(1);
		JsonNode esperado = JSON.readTree("""
				{"from":"Clama <oracao@clama.me>","to":["ana@exemplo.com"],"subject":"Assunto",
				 "text":"texto","html":"<p>html</p>","reply_to":["contato@clama.me"]}""");
		assertThat(JSON.readTree(corpos.get(0))).isEqualTo(esperado);
		assertThat(headers.get(0)).containsExactly("POST", "Bearer re_chave", "application/json", "application/json", null);
	}

	@Test
	void chaveDeIdempotenciaViraHeaderEmTodaTentativa() {
		respostas.addAll(List.of(500, 200));
		enviador().enviar(EMAIL.comChave("oracao/42"));
		assertThat(headers).extracting(h -> h[4]).containsExactly("oracao/42", "oracao/42");
		assertThat(JSON.readTree(corpos.get(0))).isEqualTo(JSON.readTree(corpos.get(1)));
	}

	@Test
	void chaveForaDoLimiteDaResendNaoMonta() {
		assertThatThrownBy(() -> EMAIL.comChave("")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> EMAIL.comChave("x".repeat(257))).isInstanceOf(IllegalArgumentException.class);
		assertThat(EMAIL.comChave("x".repeat(256)).chaveDeIdempotencia()).hasSize(256);
	}

	@Test
	void semHtmlNaoMandaACampo() throws Exception {
		enviador().enviar(new Email("ana@exemplo.com", "A", "t", null));
		assertThat(JSON.readTree(corpos.get(0)).has("html")).isFalse();
	}

	@Test
	void retenta5xxEPassa() {
		respostas.addAll(List.of(500, 500, 200));
		enviador().enviar(EMAIL);
		assertThat(corpos).hasSize(3);
	}

	@Test
	void naoRetenta4xx() {
		respostas.add(422);
		assertThatThrownBy(() -> enviador().enviar(EMAIL)).isInstanceOf(HttpStatusException.class);
		assertThat(corpos).hasSize(1);
		assertThat(dormiu).isEmpty();
	}

	@Test
	void redirect3xxEErroSemRetentar() {
		respostas.add(301);
		assertThatThrownBy(() -> enviador().enviar(EMAIL)).isInstanceOf(HttpStatusException.class)
				.hasMessageStartingWith("HTTP 301");
		assertThat(corpos).hasSize(1);
		assertThat(dormiu).isEmpty();
	}

	@Test
	void corpoDoErroVaiNaMensagem() {
		respostas.add(422);
		assertThatThrownBy(() -> enviador().enviar(EMAIL))
				.hasMessage("HTTP 422: {\"name\":\"validation_error\",\"message\":\"Invalid `to` field\"}");
	}

	@Test
	void mensagemCortaOCorpoEm500() {
		assertThat(new HttpStatusException(500, "x".repeat(800)).getMessage()).isEqualTo("HTTP 500: " + "x".repeat(500));
	}

	@Test
	void desisteDepoisDe3() {
		respostas.addAll(List.of(503, 503, 503, 503));
		assertThatThrownBy(() -> enviador().enviar(EMAIL)).isInstanceOf(HttpStatusException.class);
		assertThat(corpos).hasSize(3);
		assertThat(dormiu).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
	}

	@Test
	void semChaveFalhaNoEnvioNaoNoBoot() {
		new ApplicationContextRunner()
				.withUserConfiguration(EmailConfig.class)
				.withBean(JsonMapper.class, () -> JSON)
				.withInitializer(c -> c.getEnvironment().setActiveProfiles("prod"))
				.run(ctx -> {
					assertThat(ctx).hasNotFailed();
					assertThatThrownBy(() -> ctx.getBean(EnviadorDeEmail.class).enviar(EMAIL))
							.isInstanceOf(IllegalStateException.class)
							.hasMessage("RESEND_API_KEY ausente");
				});
	}

	@Test
	void perfilTestUsaCaixaEmMemoria() {
		new ApplicationContextRunner()
				.withUserConfiguration(EmailConfig.class)
				.withBean(JsonMapper.class, () -> JSON)
				.withPropertyValues("spring.profiles.active=test")
				.run(ctx -> {
					EnviadorDeEmail e = ctx.getBean(EnviadorDeEmail.class);
					assertThat(e).isInstanceOf(CaixaDeSaidaEmMemoria.class);
					assertThat(e.enviar(EMAIL)).isEqualTo(ResultadoEnvio.ENVIADO);
					assertThat(((CaixaDeSaidaEmMemoria) e).enviados()).containsExactly(EMAIL);
					((CaixaDeSaidaEmMemoria) e).limpar();
					assertThat(((CaixaDeSaidaEmMemoria) e).enviados()).isEmpty();
				});
	}
}
