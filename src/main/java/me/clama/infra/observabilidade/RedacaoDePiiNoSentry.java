package me.clama.infra.observabilidade;

import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.SentryException;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * INV:28. Tira PII (LGPD) do evento antes de sair: corpo, cookies e headers da requisicao somem, e e-mail e CPF
 * viram marcadores em mensagens e valores de excecao. O {@code send-default-pii: false} continua ligado.
 */
@Configuration(proxyBeanMethods = false)
public class RedacaoDePiiNoSentry {

	private static final Pattern EMAIL = Pattern.compile("[\\w.+\\-]+@[a-zA-Z0-9\\-]+\\.[a-zA-Z0-9\\-.]+");
	private static final Pattern CPF = Pattern.compile("(?<!\\d)\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2}(?!\\d)");

	@Bean
	SentryOptions.BeforeSendCallback redacaoDePii() {
		return (evento, dica) -> redigir(evento);
	}

	static SentryEvent redigir(SentryEvent e) {
		var req = e.getRequest();
		if (req != null) {
			req.setData(null);
			req.setCookies(null);
			req.setHeaders(null); // Authorization, Cookie, X-XSRF-TOKEN e o IP do XFF
			req.setQueryString(null);
		}
		if (e.getMessage() != null) {
			var m = e.getMessage();
			m.setMessage(limpar(m.getMessage()));
			m.setFormatted(limpar(m.getFormatted()));
		}
		if (e.getExceptions() != null) {
			for (SentryException x : e.getExceptions()) x.setValue(limpar(x.getValue()));
		}
		return e;
	}

	static String limpar(String s) {
		if (s == null) return null;
		return CPF.matcher(EMAIL.matcher(s).replaceAll("[EMAIL]")).replaceAll("[CPF]");
	}
}
