package me.clama.infra.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Fora de local/teste, e-mail sem chave ou com link para localhost e erro de configuracao: avisa alto, sem barrar o boot. */
@Component
class AvisoDeConfiguracao {

	private static final Logger LOG = LoggerFactory.getLogger(AvisoDeConfiguracao.class);

	@EventListener(ApplicationReadyEvent.class)
	void conferir(ApplicationReadyEvent evento) {
		Environment env = evento.getApplicationContext().getEnvironment();
		if (env.acceptsProfiles(Profiles.of("local", "test"))) return;
		var frontend = env.getProperty("clama.conta.frontend-url", "");
		if (frontend.isBlank() || frontend.contains("localhost")) {
			LOG.error("FRONTEND_URL ausente ou apontando para localhost: os links dos e-mails de senha vao quebrar");
		}
		if (env.getProperty("clama.email.resend-api-key", "").isBlank()) {
			LOG.error("RESEND_API_KEY ausente: nenhum e-mail (esqueci a senha, definir senha) sera enviado");
		}
	}
}
