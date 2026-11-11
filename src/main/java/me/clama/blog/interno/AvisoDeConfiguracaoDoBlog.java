package me.clama.blog.interno;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Fora de local/teste, WORDPRESS_API_URL vazio e erro de configuracao: o POST do blog responde 503 e a reconciliacao nao roda. Avisa alto, sem barrar o boot. */
@Component
class AvisoDeConfiguracaoDoBlog {

	private static final Logger LOG = LoggerFactory.getLogger(AvisoDeConfiguracaoDoBlog.class);

	private final BlogProps props;

	AvisoDeConfiguracaoDoBlog(BlogProps props) {
		this.props = props;
	}

	@EventListener(ApplicationReadyEvent.class)
	void aoIniciar(ApplicationReadyEvent evento) {
		conferir(evento.getApplicationContext().getEnvironment());
	}

	void conferir(Environment env) {
		if (env.acceptsProfiles(Profiles.of("local", "test"))) return;
		if (props.wordpressUrl().isBlank()) {
			LOG.error("WORDPRESS_API_URL ausente: comentarios e curtidas do blog respondem 503 e o espelho dos posts nao e reconciliado");
		}
	}
}
