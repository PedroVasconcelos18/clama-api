package me.clama.infra.email;

import com.resend.Resend;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EmailProps.class)
class ConfigDeEmail {

	@Bean
	EnviadorDeEmail enviadorDeEmail(EmailProps props) {
		var emails = props.resendApiKey().isBlank() ? null : new Resend(props.resendApiKey()).emails();
		return new ResendEmail(emails, props.remetente(), ResendEmail::dormir);
	}

	/** Relogio unico da aplicacao (testes trocam por um que avanca). */
	@Bean
	Clock relogio() {
		return Clock.systemUTC();
	}
}
