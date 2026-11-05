package me.clama.api.comum.observabilidade;

import static org.assertj.core.api.Assertions.assertThat;

import io.sentry.Sentry;
import io.sentry.spring.boot4.SentryAutoConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SentryDesligadoTest {
	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(SentryAutoConfiguration.class))
			.withUserConfiguration(SentryConfig.class);

	@AfterEach
	void fechar() {
		Sentry.close();
	}

	@Test
	void semDsnNaoInicializa() {
		runner.run(ctx -> assertThat(Sentry.isEnabled()).isFalse());
	}

	@Test
	void dsnVazioTambemNaoInicializa() {
		runner.withPropertyValues("sentry.dsn=").run(ctx -> assertThat(Sentry.isEnabled()).isFalse());
	}

	@Test
	void taxaDeTracesPorAmbiente() {
		assertThat(SentryConfig.taxaDeTraces("production")).isEqualTo(0.1);
		assertThat(SentryConfig.taxaDeTraces("local")).isEqualTo(1.0);
		assertThat(SentryConfig.taxaDeTraces("staging")).isEqualTo(1.0);
	}

	@Test
	void comDsnLigaComAmbienteETaxaDoDjango() {
		var transport = new TransportEmMemoria();
		runner.withBean(io.sentry.ITransportFactory.class, transport::fabrica)
				.withPropertyValues("sentry.dsn=https://chave@localhost/1", "sentry.environment=production")
				.run(ctx -> {
					var o = Sentry.getCurrentScopes().getOptions();
					assertThat(o.getTracesSampleRate()).isEqualTo(0.1);
					assertThat(o.getProfilesSampleRate()).isEqualTo(0.0);
					assertThat(o.isSendDefaultPii()).isFalse();
					assertThat(o.getEnvironment()).isEqualTo("production");
				});
	}

	@Test
	void semAmbienteUsaLocalComoODjango() {
		var transport = new TransportEmMemoria();
		runner.withBean(io.sentry.ITransportFactory.class, transport::fabrica)
				.withPropertyValues("sentry.dsn=https://chave@localhost/1")
				.run(ctx -> {
					var o = Sentry.getCurrentScopes().getOptions();
					assertThat(o.getEnvironment()).isEqualTo("local");
					assertThat(o.getTracesSampleRate()).isEqualTo(1.0);
				});
	}
}
