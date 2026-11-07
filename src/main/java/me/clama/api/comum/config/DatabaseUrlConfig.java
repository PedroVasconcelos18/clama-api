package me.clama.api.comum.config;

import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Monta o DataSource a partir de DATABASE_URL (formato Railway/Django), quando presente. */
@Configuration
public class DatabaseUrlConfig {

	public record JdbcParts(String url, String usuario, String senha) {
	}

	public static JdbcParts paraJdbc(String databaseUrl) {
		URI uri = URI.create(databaseUrl);
		String[] cred = uri.getRawUserInfo().split(":", 2);
		String host = uri.getHost();
		boolean local = host.equals("localhost") || host.equals("127.0.0.1");
		String url = "jdbc:postgresql://" + host + ":" + uri.getPort() + uri.getPath()
				+ (local ? "" : "?sslmode=require");
		return new JdbcParts(url, decode(cred[0]), cred.length > 1 ? decode(cred[1]) : "");
	}

	private static String decode(String s) {
		return URLDecoder.decode(s, StandardCharsets.UTF_8);
	}

	/** Fora do perfil test: la o banco e o do Testcontainers, nunca o DATABASE_URL que estiver no shell. */
	@Bean
	@Profile("!test")
	@ConditionalOnProperty("DATABASE_URL")
	@ConfigurationProperties("spring.datasource.hikari")
	HikariDataSource dataSource(@Value("${DATABASE_URL}") String databaseUrl) {
		JdbcParts p = paraJdbc(databaseUrl);
		// o checkout segura uma conexao durante a chamada ao Mercado Pago: 20 (nao 10) e espera curta (10 s, nao 30).
		// Os valores de spring.datasource.hikari.* sobrescrevem estes (bind depois do bean criado).
		HikariDataSource ds = new HikariDataSource();
		ds.setJdbcUrl(p.url());
		ds.setUsername(p.usuario());
		ds.setPassword(p.senha());
		ds.setMaximumPoolSize(20);
		ds.setConnectionTimeout(10_000);
		// o Detail de uma violacao de unique traz o e-mail e o Hibernate o loga: o driver nem o poe na mensagem
		ds.addDataSourceProperty("logServerErrorDetail", "false");
		return ds;
	}
}
