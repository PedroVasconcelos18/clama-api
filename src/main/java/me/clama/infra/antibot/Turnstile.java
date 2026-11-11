package me.clama.infra.antibot;

import io.sentry.Sentry;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/** Verificacao anti-robo da Cloudflare. Indisponibilidade conta como "nao humano" (false), nunca como excecao. */
public class Turnstile {

	static final String CHAVE_DE_TESTE = "1x0000000000000000000000000000000AA";
	private static final Logger LOG = LoggerFactory.getLogger(Turnstile.class);

	@HttpExchange
	interface Cliente {
		@PostExchange(contentType = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
		Map<String, Object> siteverify(@RequestBody LinkedMultiValueMap<String, String> form);
	}

	private final Cliente cliente;
	private final String secret;
	private final boolean aceitaChaveDeTeste;

	/** @param aceitaChaveDeTeste true so nos perfis local e de teste: la a chave de teste da Cloudflare aprova sem rede. */
	public Turnstile(String url, String secret, boolean aceitaChaveDeTeste) {
		var fabrica = new SimpleClientHttpRequestFactory();
		fabrica.setConnectTimeout(Duration.ofSeconds(5));
		fabrica.setReadTimeout(Duration.ofSeconds(10));
		var rest = RestClient.builder().baseUrl(url).requestFactory(fabrica).build();
		this.cliente = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rest)).build().createClient(Cliente.class);
		this.secret = secret == null ? "" : secret;
		this.aceitaChaveDeTeste = aceitaChaveDeTeste;
	}

	public boolean verificar(String token, String ip) {
		if (token == null || token.isBlank()) return false;
		if (secret.isBlank()) {
			LOG.error("clama.turnstile.secret nao configurado: toda verificacao anti-robo falha");
			return false;
		}
		if (aceitaChaveDeTeste && secret.equals(CHAVE_DE_TESTE)) return true;
		var form = new LinkedMultiValueMap<String, String>();
		form.add("secret", secret);
		form.add("response", token);
		if (ip != null && !ip.isBlank()) form.add("remoteip", ip);
		try {
			var resposta = cliente.siteverify(form);
			return resposta != null && Boolean.TRUE.equals(resposta.get("success"));
		} catch (RuntimeException e) {
			LOG.warn("Turnstile indisponivel: {}", e.getMessage());
			Sentry.captureException(e);
			return false;
		}
	}
}
