package me.clama;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Postgres real para testes com banco: {@code @Import(BancoDeTeste.class)}. */
@TestConfiguration(proxyBeanMethods = false)
public class BancoDeTeste {

	/**
	 * Quem enfileira e comita limpa a fila no {@code @BeforeEach} e no {@code @AfterEach} ({@code @FilaLigada} ja faz):
	 * {@code jdbc.update(BancoDeTeste.LIMPAR_FILA)}.
	 */
	public static final String LIMPAR_FILA = "delete from scheduled_tasks";

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgres() {
		return new PostgreSQLContainer("postgres:18");
	}
}
