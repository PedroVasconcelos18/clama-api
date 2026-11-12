package me.clama.infra.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;

class ConfiguracaoObrigatoriaTest {

	private static MockEnvironment producaoCompleta() {
		return new MockEnvironment()
				.withProperty("clama.pagamento.mercadopago-token", "t")
				.withProperty("clama.oracao.anthropic-api-key", "k")
				.withProperty("clama.turnstile.secret", "s")
				.withProperty("clama.email.resend-api-key", "r");
	}

	@ParameterizedTest
	@CsvSource({
			"clama.pagamento.mercadopago-token, MERCADOPAGO_ACCESS_TOKEN",
			"clama.oracao.anthropic-api-key, ANTHROPIC_API_KEY",
			"clama.turnstile.secret, TURNSTILE_SECRET_KEY",
			"clama.email.resend-api-key, RESEND_API_KEY" })
	void producaoSemUmaDasChavesNaoSobe(String propriedade, String variavel) {
		var env = producaoCompleta().withProperty(propriedade, " ");
		assertThatThrownBy(() -> ConfiguracaoObrigatoria.conferir(env))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining(variavel);
	}

	@Test
	void producaoCompletaSobe() {
		assertThatCode(() -> ConfiguracaoObrigatoria.conferir(producaoCompleta())).doesNotThrowAnyException();
	}

	@ParameterizedTest
	@CsvSource({ "local", "test" })
	void localETesteSobemSemNada(String perfil) {
		var env = new MockEnvironment();
		env.setActiveProfiles(perfil);
		assertThatCode(() -> ConfiguracaoObrigatoria.conferir(env)).doesNotThrowAnyException();
	}
}
