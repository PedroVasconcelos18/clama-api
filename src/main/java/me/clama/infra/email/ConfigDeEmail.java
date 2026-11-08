package me.clama.infra.email;

import com.resend.Resend;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EmailProps.class)
class ConfigDeEmail {

	private static final Logger LOG = LoggerFactory.getLogger(ConfigDeEmail.class);

	/** Em dev local sem RESEND_API_KEY o e-mail vai para o log (INFO): quem testa o fluxo de senha le o link dali. */
	@Bean
	EnviadorDeEmail enviadorDeEmail(EmailProps props, Environment env) {
		if (props.resendApiKey().isBlank() && env.acceptsProfiles(Profiles.of("local"))) {
			return (email, chave) -> LOG.info("E-mail local para {}: {}\n{}", email.para(), email.assunto(), email.texto());
		}
		var emails = props.resendApiKey().isBlank() ? null : new Resend(props.resendApiKey()).emails();
		return new ResendEmail(emails, props.remetente(), ResendEmail::dormir);
	}

	/** Relogio unico da aplicacao (testes trocam por um que avanca). */
	@Bean
	Clock relogio() {
		return Clock.systemUTC();
	}
}
