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

	private static final String HIKARI = "spring.datasource.hikari.data-source-properties.";

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
		String url = env.getProperty("DATABASE_URL");
		if (url == null || url.isBlank()) {
			return;
		}
		URI uri = URI.create(url);
		String host = uri.getHost();
		boolean local = "localhost".equals(host) || "127.0.0.1".equals(host);
		String[] credenciais = uri.getRawUserInfo() == null ? new String[0] : uri.getRawUserInfo().split(":", 2);

		var props = new HashMap<String, Object>();
		props.put("spring.datasource.url",
				"jdbc:postgresql://" + host + ":" + uri.getPort() + uri.getPath() + (local ? "" : "?sslmode=require"));
		if (credenciais.length > 0) {
			props.put("spring.datasource.username", decodifica(credenciais[0]));
		}
		if (credenciais.length > 1) {
			props.put("spring.datasource.password", decodifica(credenciais[1]));
		}
		// sem estes timeouts o proxy publico do Railway ja travou uma conexao para sempre (incidente 2026-11-08)
		props.put(HIKARI + "connectTimeout", "10");
		props.put(HIKARI + "loginTimeout", "20");
		props.put(HIKARI + "tcpKeepAlive", "true");
		props.put(HIKARI + "logServerErrorDetail", "false");
		env.getPropertySources().addLast(new MapPropertySource("databaseUrl", props));
	}

	private static String decodifica(String s) {
		return URLDecoder.decode(s, StandardCharsets.UTF_8);
	}
}
