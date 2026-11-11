package me.clama.infra.antibot;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.sentry.Sentry;
import org.junit.jupiter.api.Test;

@WireMockTest
class TurnstileTest {

	private static Turnstile turnstile(WireMockRuntimeInfo wm, String secret, boolean aceitaChaveDeTeste) {
		return new Turnstile(wm.getHttpBaseUrl() + "/siteverify", secret, aceitaChaveDeTeste);
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
