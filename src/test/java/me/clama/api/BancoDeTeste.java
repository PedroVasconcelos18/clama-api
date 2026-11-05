package me.clama.api;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Postgres na mesma major de producao (18), com o schema de producao carregado. */
@TestConfiguration(proxyBeanMethods = false)
public class BancoDeTeste {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgres() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:18"))
				.withInitScript("db/schema-prod.sql");
	}
}
