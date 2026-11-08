package me.clama.infra.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Traduz o DATABASE_URL do Railway ({@code postgres[ql]://usuario:senha@host:porta/banco}) em
 * {@code spring.datasource.*}. Entra por ultimo: qualquer spring.datasource.* explicito vence.
 */
public class DatabaseUrlPostProcessor implements EnvironmentPostProcessor {

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
		String url = env.getProperty("DATABASE_URL");
		if (url == null || url.isBlank()) {
			return;
		}
		URI uri;
		try {
			uri = URI.create(url);
		} catch (IllegalArgumentException e) {
			throw invalida();
		}
		String host = uri.getHost();
		if (host == null) {
			throw invalida();
		}
		boolean local = "localhost".equals(host) || "127.0.0.1".equals(host);
		String[] credenciais = uri.getRawUserInfo() == null ? new String[0] : uri.getRawUserInfo().split(":", 2);

		var props = new HashMap<String, Object>();
		props.put("spring.datasource.url",
				"jdbc:postgresql://" + host + (uri.getPort() < 0 ? "" : ":" + uri.getPort()) + uri.getPath() + (local ? "" : "?sslmode=require"));
		if (credenciais.length > 0) {
			props.put("spring.datasource.username", decodifica(credenciais[0]));
		}
		if (credenciais.length > 1) {
			props.put("spring.datasource.password", decodifica(credenciais[1]));
		}
		env.getPropertySources().addLast(new MapPropertySource("databaseUrl", props));
	}

	// nunca incluir a URL na mensagem: ela carrega a senha
	private static IllegalArgumentException invalida() {
		return new IllegalArgumentException(
				"DATABASE_URL invalida: esperado postgres[ql]://usuario:senha@host:porta/banco (caracteres especiais da senha devem ir codificados)");
	}

	private static String decodifica(String s) {
		return URLDecoder.decode(s, StandardCharsets.UTF_8);
	}
}
