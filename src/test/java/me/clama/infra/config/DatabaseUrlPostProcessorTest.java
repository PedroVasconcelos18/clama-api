package me.clama.infra.config;

import static org.assertj.core.api.Assertions.assertThat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

class DatabaseUrlPostProcessorTest {

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
	void semDatabaseUrlNaoFazNada() {
		var env = processa(new MockEnvironment());
		assertThat(env.getProperty("spring.datasource.url")).isNull();
		assertThat(env.getProperty("spring.datasource.username")).isNull();
	}

	@Test
	void datasourceExplicitoVence() {
		var env = processa(new MockEnvironment()
				.withProperty("spring.datasource.url", "jdbc:postgresql://outro/x")
				.withProperty("DATABASE_URL", "postgres://u:p@localhost:5432/clama"));
		assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://outro/x");
	}

	@Test
	void urlInvalidaFalhaSemVazarSegredo() {
		assertThatThrownBy(() -> processa(new MockEnvironment().withProperty("DATABASE_URL", "postgresql://u:p@x@h:5432/c")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageNotContaining("p@x")
				.hasMessageNotContaining("postgresql://");
	}

	@Test
	void semPortaNaoGeraMenosUm() {
		var env = processa(new MockEnvironment().withProperty("DATABASE_URL", "postgresql://u:p@h.rlwy.net/clama"));
		assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://h.rlwy.net/clama?sslmode=require");
	}

	@Test
	void senhaComDoisPontos() {
		var env = processa(new MockEnvironment().withProperty("DATABASE_URL", "postgres://u:p:w@h:5432/c"));
		assertThat(env.getProperty("spring.datasource.password")).isEqualTo("p:w");
	}

	@Test
	void propriedadesChegamAoHikari() throws Exception {
		var env = processa(new MockEnvironment().withProperty("DATABASE_URL", "postgres://u:p@localhost:5432/clama"));
		// timeouts do driver vivem no application.yml (valem com ou sem DATABASE_URL)
		for (var fonte : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))) {
			env.getPropertySources().addLast(fonte);
		}
		var binder = Binder.get(env);
		var props = binder.bind("spring.datasource", DataSourceProperties.class).get();
		var hikari = binder.bind("spring.datasource.hikari", HikariDataSource.class).get();
		assertThat(props.getUrl()).isEqualTo("jdbc:postgresql://localhost:5432/clama");
		assertThat(hikari.getDataSourceProperties()).containsEntry("connectTimeout", "10")
				.containsEntry("loginTimeout", "20").containsEntry("tcpKeepAlive", "true")
				.containsEntry("logServerErrorDetail", "false");
	}
}
