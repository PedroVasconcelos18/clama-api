package me.clama.api.comum.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DatabaseUrlConfigTest {

	@Test
	void converteUrlDoRailway() {
		var p = DatabaseUrlConfig.paraJdbc("postgresql://u:p%40ss@h.proxy.rlwy.net:12345/clama");
		assertThat(p.url()).isEqualTo("jdbc:postgresql://h.proxy.rlwy.net:12345/clama?sslmode=require");
		assertThat(p.usuario()).isEqualTo("u");
		assertThat(p.senha()).isEqualTo("p@ss");
	}

	@Test
	void aceitaPrefixoPostgresELocalhostSemSsl() {
		var p = DatabaseUrlConfig.paraJdbc("postgres://u:p@localhost:5432/clama");
		assertThat(p.url()).isEqualTo("jdbc:postgresql://localhost:5432/clama");
	}

	@org.springframework.context.annotation.Configuration
	@org.springframework.boot.context.properties.EnableConfigurationProperties
	static class Habilita {
	}

	private org.springframework.boot.test.context.runner.ApplicationContextRunner runner() {
		return new org.springframework.boot.test.context.runner.ApplicationContextRunner()
				.withUserConfiguration(Habilita.class, DatabaseUrlConfig.class)
				.withPropertyValues("DATABASE_URL=postgres://u:p@localhost:5432/clama",
						"spring.profiles.active=prod"); // o surefire ativa o perfil test
	}

	@Test
	void poolTemPadroesDeCheckout() {
		runner().run(ctx -> {
			var ds = ctx.getBean(com.zaxxer.hikari.HikariDataSource.class);
			assertThat(ds.getMaximumPoolSize()).isEqualTo(20);
			assertThat(ds.getConnectionTimeout()).isEqualTo(10_000);
			assertThat(ds.getJdbcUrl()).isEqualTo("jdbc:postgresql://localhost:5432/clama");
			assertThat(ds.getUsername()).isEqualTo("u");
		});
	}

	@Test
	void poolHonraSpringDatasourceHikari() {
		runner().withPropertyValues("spring.datasource.hikari.maximum-pool-size=7",
				"spring.datasource.hikari.connection-timeout=2500").run(ctx -> {
					var ds = ctx.getBean(com.zaxxer.hikari.HikariDataSource.class);
					assertThat(ds.getMaximumPoolSize()).isEqualTo(7);
					assertThat(ds.getConnectionTimeout()).isEqualTo(2500);
					assertThat(ds.getJdbcUrl()).isEqualTo("jdbc:postgresql://localhost:5432/clama");
				});
	}
}
