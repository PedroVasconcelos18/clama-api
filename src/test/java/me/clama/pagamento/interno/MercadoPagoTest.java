package me.clama.pagamento.interno;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mockStatic;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import me.clama.pedido.PedidoCobravel;
import me.clama.pedido.StatusPedido;
import org.junit.jupiter.api.Test;
import io.sentry.Sentry;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

/** O SDK do Mercado Pago contra um WireMock: o que sai na rede (corpo, chave, token) e como as falhas voltam. */
@WireMockTest
class MercadoPagoTest {

	static final UUID ID = UUID.fromString("77777777-7777-4777-8777-000000000001");
	static final String CHAVE = "clama-pedido-" + ID;
	static final Instant EXPIRA = Instant.parse("2026-11-12T12:00:00Z");
	static final String PIX_CRIADO;

	static {
		try {
			PIX_CRIADO = Files.readString(Path.of("src/test/resources/mercadopago/pix_criado.json"));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	final List<Duration> esperas = new ArrayList<>();

	MercadoPago mp(WireMockRuntimeInfo wm, String token, String webhook) {
		var props = new PagamentoProps(100, token, wm.getHttpBaseUrl(), Duration.ofMillis(300), webhook);
		return new MercadoPago(props, esperas::add);
	}

	MercadoPago mp(WireMockRuntimeInfo wm) {
		return mp(wm, "test", "https://api.clama.test/api/webhooks/mercadopago");
	}

	static PedidoCobravel pedido(long centavos, String nome, String cpfCnpj) {
		return new PedidoCobravel(ID, StatusPedido.AGUARDANDO_PAGAMENTO, centavos, nome, "maria@exemplo.com", cpfCnpj);
	}

	static PedidoCobravel pedido() {
		return pedido(3000, "  Maria Silva ", "52998224725");
	}

	static void criaResponde(int status, String corpo) {
		stubFor(post("/v1/payments").willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(corpo)));
	}

	@Test
	void criaPixComOCorpoEAChaveDoPedido(WireMockRuntimeInfo wm) {
		criaResponde(201, PIX_CRIADO);

		var pix = mp(wm).criarPix(pedido(), CHAVE, EXPIRA);

		assertThat(pix.id()).isEqualTo(1325814423L);
		assertThat(pix.copiaECola()).startsWith("00020126380014br.gov.bcb.pix");
		assertThat(pix.qrBase64()).startsWith("iVBORw0KGgo");
		verify(postRequestedFor(urlEqualTo("/v1/payments")).withRequestBody(equalToJson("""
				{"transaction_amount": 30.00, "description": "Pedido Clama #77777777", "payment_method_id": "pix",
				 "external_reference": "77777777-7777-4777-8777-000000000001",
				 "date_of_expiration": "2026-11-12T09:00:00.000-03:00",
				 "notification_url": "https://api.clama.test/api/webhooks/mercadopago",
				 "payer": {"email": "maria@exemplo.com", "first_name": "Maria Silva",
				           "identification": {"type": "CPF", "number": "52998224725"}}}""")));
		var req = getAllServeEvents().getFirst().getRequest();
		// o SDK poe a chave aleatoria dele e o token global; so pode sair um de cada, o nosso
		assertThat(req.getHeaders().getHeader("X-Idempotency-Key").values()).containsExactly(CHAVE);
		assertThat(req.getHeaders().getHeader("Authorization").values()).containsExactly("Bearer test");
	}

	@Test
	void valorEnviadoEhExato(WireMockRuntimeInfo wm) {
		criaResponde(201, PIX_CRIADO);
		mp(wm).criarPix(pedido(1234, "Maria", "52998224725"), CHAVE, EXPIRA);
		mp(wm).criarPix(pedido(2147483647, "Maria", "52998224725"), CHAVE, EXPIRA);

		var corpos = getAllServeEvents().reversed().stream().map(e -> e.getRequest().getBodyAsString()).toList();
		// nunca float: 21474836.47 em double viraria 2.147483647E7
		assertThat(corpos.get(0)).contains("\"transaction_amount\":12.34");
		assertThat(corpos.get(1)).contains("\"transaction_amount\":21474836.47");
	}

	@Test
	void cnpjSemNomeESemNotificationUrlForaDeHttps(WireMockRuntimeInfo wm) {
		criaResponde(201, PIX_CRIADO);
		mp(wm, "test", "http://api.clama.test/api/webhooks/mercadopago").criarPix(pedido(5000, "   ", "11222333000181"), CHAVE, EXPIRA);
		mp(wm, "test", "").criarPix(pedido(5000, null, "11222333000181"), CHAVE, EXPIRA);

		verify(2, postRequestedFor(urlEqualTo("/v1/payments"))
				.withRequestBody(matchingJsonPath("$.payer", equalToJson("""
						{"email": "maria@exemplo.com", "identification": {"type": "CNPJ", "number": "11222333000181"}}""")))
				.withRequestBody(notContaining("notification_url")));
	}

	@Test
	void retentativaDepoisDeTimeoutUsaAMesmaChave(WireMockRuntimeInfo wm) {
		stubFor(post("/v1/payments").inScenario("r").whenScenarioStateIs(Scenario.STARTED).willSetStateTo("2")
				.willReturn(okJson(PIX_CRIADO).withFixedDelay(1500)));
		stubFor(post("/v1/payments").inScenario("r").whenScenarioStateIs("2").willSetStateTo("3")
				.willReturn(aResponse().withStatus(409).withBody("{\"message\":\"in process\",\"cause\":[]}")));
		stubFor(post("/v1/payments").inScenario("r").whenScenarioStateIs("3").willReturn(okJson(PIX_CRIADO)));

		var pix = mp(wm).criarPix(pedido(), CHAVE, EXPIRA);

		assertThat(pix.id()).isEqualTo(1325814423L);
		var reqs = getAllServeEvents().stream().map(e -> e.getRequest()).toList();
		assertThat(reqs).hasSize(3).allSatisfy(r -> assertThat(r.getHeaders().getHeader("X-Idempotency-Key").values()).containsExactly(CHAVE));
		assertThat(reqs.get(0).getBodyAsString()).isEqualTo(reqs.get(2).getBodyAsString());
		assertThat(esperas).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
	}

	@Test
	void status423EhRetentado(WireMockRuntimeInfo wm) {
		stubFor(post("/v1/payments").inScenario("r").whenScenarioStateIs(Scenario.STARTED).willSetStateTo("2")
				.willReturn(aResponse().withStatus(423).withBody("{}")));
		stubFor(post("/v1/payments").inScenario("r").whenScenarioStateIs("2").willReturn(okJson(PIX_CRIADO)));

		assertThat(mp(wm).criarPix(pedido(), CHAVE, EXPIRA).id()).isEqualTo(1325814423L);
		assertThat(esperas).containsExactly(Duration.ofSeconds(1));
	}

	@Test
	void cincoXXRetentaTresVezesComEsperasDe1e2SegundosEFalha(WireMockRuntimeInfo wm) {
		criaResponde(500, "{\"message\":\"Internal server error\",\"cause\":[]}");

		var e = catchThrowableOfType(MercadoPago.Falha.class, () -> mp(wm).criarPix(pedido(), CHAVE, EXPIRA));

		assertThat(e.status()).isEqualTo(500);
		verify(3, postRequestedFor(urlEqualTo("/v1/payments")));
		assertThat(esperas).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
	}

	@Test
	void cincoXXForaDaListaNaoRetenta(WireMockRuntimeInfo wm) {
		criaResponde(501, "{}");
		assertThat(catchThrowableOfType(MercadoPago.Falha.class, () -> mp(wm).criarPix(pedido(), CHAVE, EXPIRA)).status()).isEqualTo(501);
		verify(1, postRequestedFor(urlEqualTo("/v1/payments")));
	}

	@Test
	void quatroXXNaoRetentaETrazADescricao(WireMockRuntimeInfo wm) {
		criaResponde(400, "{\"message\":\"Unauthorized\",\"cause\":[{\"code\":2067,\"description\":\"Invalid user identification number\"}]}");

		var e = catchThrowableOfType(MercadoPago.Falha.class, () -> mp(wm).criarPix(pedido(), CHAVE, EXPIRA));

		assertThat(e.status()).isEqualTo(400);
		assertThat(e.descricao()).isEqualTo("Invalid user identification number"); // a causa vence a message
		verify(1, postRequestedFor(urlEqualTo("/v1/payments")));
		assertThat(esperas).isEmpty();
	}

	@Test
	void timeoutEsgotadoFalhaDepoisDeTresTentativas(WireMockRuntimeInfo wm) {
		stubFor(post("/v1/payments").willReturn(okJson(PIX_CRIADO).withFixedDelay(1500)));

		var e = catchThrowableOfType(MercadoPago.Falha.class, () -> mp(wm).criarPix(pedido(), CHAVE, EXPIRA));

		assertThat(e.quatroXX()).isFalse();
		verify(3, postRequestedFor(urlEqualTo("/v1/payments")));
	}

	@Test
	void respostaSemQrFalhaSemRetentar(WireMockRuntimeInfo wm) {
		criaResponde(201, "{\"id\": 1325814425, \"status\": \"pending\"}");

		var e = catchThrowableOfType(MercadoPago.Falha.class, () -> mp(wm).criarPix(pedido(), CHAVE, EXPIRA));

		assertThat(e.quatroXX()).isFalse();
		verify(1, postRequestedFor(urlEqualTo("/v1/payments")));
	}

	@Test
	void semTokenFalhaSemChamarARede(WireMockRuntimeInfo wm) {
		var e = catchThrowableOfType(MercadoPago.Falha.class, () -> mp(wm, "", "").criarPix(pedido(), CHAVE, EXPIRA));
		assertThat(e.quatroXX()).isFalse();
		verify(0, anyRequestedFor(anyUrl()));
	}

	@Test
	void tokenNuncaApareceEmLogNemEmExcecao(WireMockRuntimeInfo wm) {
		var token = "APP_USR-SEGREDO-0123456789";
		var raiz = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		var logs = new ListAppender<ILoggingEvent>();
		logs.start();
		raiz.addAppender(logs);
		var erros = new ArrayList<Throwable>();
		try {
			for (var caso : List.of(aResponse().withStatus(401).withBody("{\"message\":\"Unauthorized access token\"}"),
					aResponse().withStatus(500).withBody("{}"), okJson(PIX_CRIADO).withFixedDelay(1500),
					okJson("{\"id\": 1}"), aResponse().withStatus(200).withBody("nao e json"))) {
				WireMock.reset();
				stubFor(post("/v1/payments").willReturn(caso));
				stubFor(get("/v1/payments/123").willReturn(caso));
				erros.add(catchThrowableOfType(MercadoPago.Falha.class, () -> mp(wm, token, "").criarPix(pedido(), CHAVE, EXPIRA)));
				erros.add(catchThrowableOfType(MercadoPago.Falha.class, () -> mp(wm, token, "").pagamento(123)));
			}
		} finally {
			raiz.detachAppender(logs);
		}

		erros.removeIf(java.util.Objects::isNull); // pagamento() com {"id": 1} e sucesso
		assertThat(erros).hasSize(9);
		assertThat(logs.list).isNotEmpty().allSatisfy(ev -> {
			assertThat(ev.getFormattedMessage()).doesNotContain(token);
			assertThat(String.valueOf(ev.getThrowableProxy() == null ? "" : ev.getThrowableProxy().getMessage())).doesNotContain(token);
		});
		for (var e : erros) {
			var s = new StringWriter();
			e.printStackTrace(new PrintWriter(s));
			assertThat(s.toString()).doesNotContain(token);
		}
	}

	@Test
	void pagamentoLeStatusReferenciaValorEMoeda(WireMockRuntimeInfo wm) {
		stubFor(get("/v1/payments/123").willReturn(okJson("""
				{"id": 123, "status": "approved", "external_reference": "ref-1", "transaction_amount": 30.01, "currency_id": "BRL"}""")));
		var p = mp(wm).pagamento(123);
		assertThat(p.status()).isEqualTo("approved");
		assertThat(p.referencia()).isEqualTo("ref-1");
		assertThat(p.valor()).isEqualByComparingTo("30.01");
		assertThat(p.moeda()).isEqualTo("BRL");
		stubFor(get("/v1/payments/124").willReturn(okJson("{\"id\": 124}")));
		assertThat(mp(wm).pagamento(124)).isEqualTo(new MercadoPago.Pagamento(null, null, null, null));
		verify(getRequestedFor(urlEqualTo("/v1/payments/123")).withHeader("Authorization", equalTo("Bearer test")));
	}

	@Test
	void cancelarMandaStatusCancelled(WireMockRuntimeInfo wm) {
		stubFor(put(urlPathEqualTo("/v1/payments/123")).willReturn(okJson("{\"id\": 123, \"status\": \"cancelled\"}")));
		mp(wm).cancelar(123);
		verify(putRequestedFor(urlPathEqualTo("/v1/payments/123")).withRequestBody(equalToJson("{\"status\": \"cancelled\"}")));
	}

	@Test
	void vencimentoEOQueOMpDevolve(WireMockRuntimeInfo wm) {
		criaResponde(201, PIX_CRIADO.replace("\"status\": \"pending\",",
				"\"status\": \"pending\", \"date_of_expiration\": \"2026-11-12T08:30:00.000-03:00\","));
		assertThat(mp(wm).criarPix(pedido(), CHAVE, EXPIRA).expiraEm()).isEqualTo(Instant.parse("2026-11-12T11:30:00Z"));
	}

	@Test
	void semVencimentoNaRespostaFicaOPedido(WireMockRuntimeInfo wm) {
		criaResponde(201, PIX_CRIADO);
		assertThat(mp(wm).criarPix(pedido(), CHAVE, EXPIRA).expiraEm()).isEqualTo(EXPIRA);
	}

	@Test
	void baseSemHttpsSoNosTestes(WireMockRuntimeInfo wm) {
		var props = new PagamentoProps(100, "test", wm.getHttpBaseUrl(), Duration.ofSeconds(10), "https://x/api/webhooks/mercadopago");
		assertThatThrownBy(() -> new MercadoPago(props, new MockEnvironment())).isInstanceOf(IllegalStateException.class);
		var local = new MockEnvironment();
		local.setActiveProfiles("local");
		assertThatThrownBy(() -> new MercadoPago(props, local)).isInstanceOf(IllegalStateException.class);
		var teste = new MockEnvironment();
		teste.setActiveProfiles("test");
		new MercadoPago(props, teste);
	}

	/** Sem token a chamada para antes da rede, mas o corpo (e o aviso do webhook) ja foi montado. */
	@Test
	void semWebhookForaDeLocalAvisaOSentrySoNaPrimeiraCobranca() {
		var props = new PagamentoProps(100, "", "https://api.mercadopago.com", Duration.ofSeconds(10), "/api/webhooks/mercadopago");
		var producao = new MercadoPago(props, new MockEnvironment());
		var local = new MercadoPago(props, comPerfil("local"));
		try (var sentry = mockStatic(Sentry.class)) {
			for (int i = 0; i < 2; i++) {
				catchThrowableOfType(MercadoPago.Falha.class, () -> producao.criarPix(pedido(), CHAVE, EXPIRA));
				catchThrowableOfType(MercadoPago.Falha.class, () -> local.criarPix(pedido(), CHAVE, EXPIRA));
			}
			sentry.verify(() -> Sentry.captureMessage(org.mockito.ArgumentMatchers.contains("BACKEND_PUBLIC_URL"),
					org.mockito.ArgumentMatchers.any(io.sentry.SentryLevel.class)));
			sentry.verifyNoMoreInteractions();
		}
	}

	static MockEnvironment comPerfil(String perfil) {
		var env = new MockEnvironment();
		env.setActiveProfiles(perfil);
		return env;
	}
}
