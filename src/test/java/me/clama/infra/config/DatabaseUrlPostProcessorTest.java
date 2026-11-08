package me.clama.infra.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DatabaseUrlPostProcessorTest {

	private static final String HIKARI = "spring.datasource.hikari.data-source-properties.";

	private MockEnvironment processa(MockEnvironment env) {
		new DatabaseUrlPostProcessor().postProcessEnvironment(env, null);
		return env;
	}

	@Test
	void converteUrlDoRailway() {
		var env = processa(new MockEnvironment()
				.withProperty("DATABASE_URL", "postgresql://u:p%40ss@h.proxy.rlwy.net:12345/clama"));
		assertThat(env.getProperty("spring.datasource.url"))
				.isEqualTo("jdbc:postgresql://h.proxy.rlwy.net:12345/clama?sslmode=require");
		assertThat(env.getProperty("spring.datasource.username")).isEqualTo("u");
		assertThat(env.getProperty("spring.datasource.password")).isEqualTo("p@ss");
	}

	@Test
	void localhostSemSsl() {
		var env = processa(new MockEnvironment().withProperty("DATABASE_URL", "postgres://u:p@localhost:5432/clama"));
		assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://localhost:5432/clama");
	}

	@Test
	void timeoutsDoDriver() {
		var env = processa(new MockEnvironment().withProperty("DATABASE_URL", "postgres://u:p@localhost:5432/clama"));
		assertThat(env.getProperty(HIKARI + "connectTimeout")).isEqualTo("10");
		assertThat(env.getProperty(HIKARI + "loginTimeout")).isEqualTo("20");
		assertThat(env.getProperty(HIKARI + "tcpKeepAlive")).isEqualTo("true");
		assertThat(env.getProperty(HIKARI + "logServerErrorDetail")).isEqualTo("false");
	}

	@Test
	void semDatabaseUrlNaoFazNada() {
		var env = processa(new MockEnvironment());
		assertThat(env.getProperty("spring.datasource.url")).isNull();
		assertThat(env.getProperty("spring.datasource.username")).isNull();
		assertThat(env.getProperty(HIKARI + "connectTimeout")).isNull();
	}

	@Test
	void datasourceExplicitoVence() {
		var env = processa(new MockEnvironment()
				.withProperty("spring.datasource.url", "jdbc:postgresql://outro/x")
				.withProperty("DATABASE_URL", "postgres://u:p@localhost:5432/clama"));
		assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://outro/x");
	}
}
