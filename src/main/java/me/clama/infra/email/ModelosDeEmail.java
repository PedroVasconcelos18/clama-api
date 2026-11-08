package me.clama.infra.email;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/** Renderiza {@code email/<modelo>.txt} e {@code .html}: so {@code {{chave}}} -> valor (escapado no html). */
@Component
public class ModelosDeEmail {

	private static final Pattern CHAVE = Pattern.compile("\\{\\{\\s*(\\w+)\\s*}}");

	public Email montar(String para, String assunto, String modelo, Map<String, String> contexto) {
		return new Email(para, assunto, renderizar("email/" + modelo + ".txt", contexto, false),
				renderizar("email/" + modelo + ".html", contexto, true));
	}

	private static String renderizar(String caminho, Map<String, String> contexto, boolean html) {
		try (var in = new ClassPathResource(caminho).getInputStream()) {
			var modelo = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			return CHAVE.matcher(modelo).replaceAll(m -> {
				var valor = contexto.get(m.group(1));
				if (valor == null) throw new IllegalArgumentException("sem valor para {{" + m.group(1) + "}} em " + caminho);
				return java.util.regex.Matcher.quoteReplacement(html ? HtmlUtils.htmlEscape(valor) : valor);
			});
		} catch (IOException e) {
			throw new UncheckedIOException(caminho, e);
		}
	}
}
