package me.clama.api;

import java.net.URI;
import java.util.Set;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Postgres na mesma major de producao (18), com o schema de producao carregado. */
@TestConfiguration(proxyBeanMethods = false)
public class BancoDeTeste {
	static final String BANCO = "clama_testcontainers";

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgres() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:18"))
				.withDatabaseName(BANCO)
				.withInitScript("db/schema-prod.sql");
	}

	/** Chamar antes de qualquer TRUNCATE/DELETE de limpeza: recusa se o JdbcTemplate nao for o do container. */
	public static void exigirContainer(JdbcTemplate jdbc) {
		exigirUrlDeContainer(jdbc.execute((ConnectionCallback<String>) c -> c.getMetaData().getURL()));
	}

	static void exigirUrlDeContainer(String jdbcUrl) {
		URI uri = jdbcUrl != null && jdbcUrl.startsWith("jdbc:postgresql://") ? URI.create(jdbcUrl.substring(5)) : null;
		boolean container = uri != null && Set.of("localhost", "127.0.0.1").contains(uri.getHost())
				&& ("/" + BANCO).equals(uri.getPath());
		if (!container) {
			throw new IllegalStateException("Limpeza de teste recusada: " + jdbcUrl
					+ " nao e o Postgres do Testcontainers (localhost/" + BANCO + ")");
		}
	}
}
