package me.clama.infra.antibot;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.sentry.Sentry;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

@WireMockTest
class TurnstileTest {

	private final List<Duration> esperas = new ArrayList<>();

	/** A espera do 503 so e registrada, nunca dormida. */
	private Turnstile turnstile(WireMockRuntimeInfo wm, String secret, boolean aceitaChaveDeTeste) {
		return new Turnstile(wm.getHttpBaseUrl() + "/siteverify", secret, aceitaChaveDeTeste, esperas::add);
	}

	@Test
	void aprovadoEnviaSecretTokenEIp(WireMockRuntimeInfo wm) {
		stubFor(post("/siteverify").willReturn(okJson("{\"success\":true}")));
		assertThat(turnstile(wm, "segredo", false).verificar("tok", "203.0.113.1")).isTrue();
		verify(postRequestedFor(urlEqualTo("/siteverify"))
				.withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
				.withRequestBody(containing("secret=segredo")).withRequestBody(containing("response=tok"))
				.withRequestBody(containing("remoteip=203.0.113.1")));
	}

	@Test
	void semIpNaoEnviaRemoteip(WireMockRuntimeInfo wm) {
		stubFor(post("/siteverify").willReturn(okJson("{\"success\":true}")));
		assertThat(turnstile(wm, "segredo", false).verificar("tok", null)).isTrue();
		verify(postRequestedFor(urlEqualTo("/siteverify")).withRequestBody(notMatching(".*remoteip.*")));
	}

	@Test
	void recusadoOuCorpoIlegivelDevolveFalso(WireMockRuntimeInfo wm) {
		stubFor(post("/siteverify").willReturn(okJson("{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}")));
		assertThat(turnstile(wm, "segredo", false).verificar("tok", "1.1.1.1")).isFalse();
		stubFor(post("/siteverify").willReturn(ok("<html>")));
		assertThat(turnstile(wm, "segredo", false).verificar("tok", "1.1.1.1")).isFalse();
	}

	@Test
	void indisponivelDevolveFalsoERegistraNoSentry(WireMockRuntimeInfo wm) {
		stubFor(post("/siteverify").willReturn(serviceUnavailable()));
		try (var sentry = mockStatic(Sentry.class)) {
			assertThat(turnstile(wm, "segredo", false).verificar("tok", "1.1.1.1")).isFalse();
			sentry.verify(() -> Sentry.captureException(any(Throwable.class)));
		}
	}

	@Test
	void indisponivelRetentaComUmEDoisSegundosEDesistePorFim(WireMockRuntimeInfo wm) {
		stubFor(post("/siteverify").willReturn(serviceUnavailable()));
		try (var sentry = mockStatic(Sentry.class)) {
			assertThat(turnstile(wm, "segredo", false).verificar("tok", "1.1.1.1")).isFalse();
			sentry.verify(() -> Sentry.captureException(any(Throwable.class)), times(1));
		}
		verify(3, postRequestedFor(urlEqualTo("/siteverify")));
		assertThat(esperas).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
	}

	@Test
	void indisponivelDepoisDeRecuperarAprova(WireMockRuntimeInfo wm) {
		stubFor(post("/siteverify").inScenario("s").whenScenarioStateIs(Scenario.STARTED).willReturn(serviceUnavailable()).willSetStateTo("2"));
		stubFor(post("/siteverify").inScenario("s").whenScenarioStateIs("2").willReturn(serviceUnavailable()).willSetStateTo("3"));
		stubFor(post("/siteverify").inScenario("s").whenScenarioStateIs("3").willReturn(okJson("{\"success\":true}")));
		assertThat(turnstile(wm, "segredo", false).verificar("tok", "1.1.1.1")).isTrue();
		assertThat(esperas).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
	}

	@Test
	void erroQueNaoE503NaoRetenta(WireMockRuntimeInfo wm) {
		stubFor(post("/siteverify").willReturn(serverError()));
		assertThat(turnstile(wm, "segredo", false).verificar("tok", "1.1.1.1")).isFalse();
		verify(1, postRequestedFor(urlEqualTo("/siteverify")));
		assertThat(esperas).isEmpty();
	}

	@Test
	void redeForaDoArDevolveFalsoERegistraNoSentry() {
		try (var sentry = mockStatic(Sentry.class)) {
			assertThat(new Turnstile("http://localhost:1/siteverify", "segredo", false).verificar("tok", null)).isFalse();
			sentry.verify(() -> Sentry.captureException(any(Throwable.class)));
		}
	}

	@Test
	void tokenVazioNaoChamaARede(WireMockRuntimeInfo wm) {
		var t = turnstile(wm, "segredo", false);
		assertThat(t.verificar(null, "1.1.1.1")).isFalse();
		assertThat(t.verificar("  ", "1.1.1.1")).isFalse();
		verify(0, postRequestedFor(urlEqualTo("/siteverify")));
	}

	@Test
	void chaveDeTesteDaCloudflareSoNoPerfilPermitido(WireMockRuntimeInfo wm) {
		var chave = "1x0000000000000000000000000000000AA";
		assertThat(turnstile(wm, chave, true).verificar("qualquer", null)).isTrue();
		verify(0, postRequestedFor(urlEqualTo("/siteverify")));
		stubFor(post("/siteverify").willReturn(okJson("{\"success\":false}")));
		assertThat(turnstile(wm, chave, false).verificar("qualquer", null)).isFalse();
	}

	@Test
	void semSecretFalhaFechado(WireMockRuntimeInfo wm) {
		assertThat(turnstile(wm, "", false).verificar("tok", null)).isFalse();
		verify(0, postRequestedFor(urlEqualTo("/siteverify")));
	}
}
