package me.clama.api.comum.observabilidade;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import io.sentry.SentryOptions;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Equivalente a init_sentry. Quem liga o SDK e a autoconfiguracao do sentry-spring-boot-4, e so com sentry.dsn
 * (env SENTRY_DSN): sem DSN nada inicializa. SENTRY_ENVIRONMENT vira sentry.environment (default local, como no Django).
 */
@Configuration(proxyBeanMethods = false)
public class SentryConfig {

	static double taxaDeTraces(String ambiente) {
		return "production".equals(ambiente) ? 0.1 : 1.0;
	}

	@Bean
	SentryOptions.BeforeSendCallback redacaoDePii() {
		return (evento, hint) -> RedacaoSentry.redigir(evento);
	}

	@Bean
	Sentry.OptionsConfiguration<SentryOptions> opcoesDoSentry(@Value("${sentry.environment:}") String ambiente) {
		return o -> {
			// o default do SDK e "production"; o Django usa "local"
			o.setEnvironment(ambiente.isBlank() ? "local" : ambiente);
			o.setTracesSampleRate(taxaDeTraces(o.getEnvironment()));
			o.setProfilesSampleRate(0.0);
			o.setSendDefaultPii(false);
		};
	}

	/** Falha tratada que mesmo assim precisa de alerta (job esgotado, tarefa agendada). No-op com o Sentry desligado. */
	public static void capturar(String tag, String nome, Throwable erro) {
		capturar(tag, nome, Map.of(), erro);
	}

	/** Como {@link #capturar(String, String, Throwable)}, com tags extras (ex.: id da instancia do job). */
	public static void capturar(String tag, String nome, Map<String, String> tags, Throwable erro) {
		Sentry.withScope(scope -> {
			scope.setTag(tag, nome);
			tags.forEach(scope::setTag);
			if (erro == null) {
				Sentry.captureMessage("Falha sem excecao (" + tag + " " + nome + ")", SentryLevel.ERROR);
			} else {
				Sentry.captureException(erro);
			}
		});
	}
}
