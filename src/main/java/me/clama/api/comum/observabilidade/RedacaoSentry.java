package me.clama.api.comum.observabilidade;

import io.sentry.Breadcrumb;
import io.sentry.SentryEvent;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** before_send de clama/core/sentry_config.py: tira PII (LGPD) do evento antes de sair. */
public final class RedacaoSentry {
	private static final Set<String> PII_FIELDS = Set.of("email", "telefone", "nome", "pedido_oracao", "oracao_gerada",
			"phone", "name", "password", "senha", "token", "refresh", "access");
	private static final Pattern EMAIL = Pattern.compile("[a-zA-Z0-9_.+\\-]+@[a-zA-Z0-9\\-]+\\.[a-zA-Z0-9\\-.]+");
	private static final Pattern PHONE = Pattern.compile("\\(?\\d{2}\\)?\\s*\\d{4,5}[-\\s]?\\d{4}");

	private RedacaoSentry() {}

	/** O Python so olha request.data/headers, extra, contexts, tags, breadcrumbs e exception.value. */
	public static SentryEvent redigir(SentryEvent e) {
		Request req = e.getRequest();
		if (req != null) {
			if (req.getData() instanceof Map<?, ?> m) {
				req.setData(dict(m));
			}
			if (req.getHeaders() != null) {
				req.setHeaders(strings(req.getHeaders()));
			}
		}
		if (e.getExtras() != null) {
			e.setExtras(dict(e.getExtras()));
		}
		// contextos tipados do SDK (device, os...) nao carregam PII; so os que sao Map
		if (e.getContexts() != null) {
			for (var c : new ArrayList<>(e.getContexts().entrySet())) {
				if (c.getValue() instanceof Map<?, ?> m) {
					e.getContexts().put(c.getKey(), dict(m));
				}
			}
		}
		if (e.getTags() != null) {
			e.setTags(strings(e.getTags()));
		}
		if (e.getBreadcrumbs() != null) {
			for (Breadcrumb b : e.getBreadcrumbs()) {
				if (b.getData() != null) {
					dict(b.getData()).forEach(b::setData);
				}
				if (b.getMessage() != null) {
					b.setMessage((String) valor("message", b.getMessage()));
				}
			}
		}
		if (e.getExceptions() != null) {
			for (SentryException x : e.getExceptions()) {
				if (x.getValue() != null) {
					x.setValue((String) valor("exception_value", x.getValue()));
				}
			}
		}
		return e;
	}

	static Object valor(String chave, Object v) {
		if (!(v instanceof String s)) {
			return v;
		}
		String k = chave.toLowerCase(Locale.ROOT);
		if (PII_FIELDS.stream().anyMatch(k::contains)) {
			return "[REDACTED]";
		}
		s = EMAIL.matcher(s).replaceAll("[EMAIL_REDACTED]");
		return PHONE.matcher(s).replaceAll("[PHONE_REDACTED]");
	}

	static Map<String, Object> dict(Map<?, ?> data) {
		Map<String, Object> out = new LinkedHashMap<>();
		data.forEach((k, v) -> {
			String chave = String.valueOf(k);
			if (v instanceof Map<?, ?> m) {
				out.put(chave, dict(m));
			} else if (v instanceof List<?> l) {
				out.put(chave, l.stream().map(i -> i instanceof Map<?, ?> m ? dict(m) : valor(chave, i)).toList());
			} else {
				out.put(chave, valor(chave, v));
			}
		});
		return out;
	}

	private static Map<String, String> strings(Map<String, String> m) {
		Map<String, String> out = new LinkedHashMap<>();
		m.forEach((k, v) -> out.put(k, (String) valor(k, v)));
		return out;
	}
}
