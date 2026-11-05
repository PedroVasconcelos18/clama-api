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
}
