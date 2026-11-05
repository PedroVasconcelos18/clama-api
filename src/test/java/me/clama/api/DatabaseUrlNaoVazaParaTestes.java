package me.clama.api;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Um DATABASE_URL de producao no shell nao pode virar o banco dos testes (o replay faz TRUNCATE CASCADE). */
@SpringBootTest(properties = "DATABASE_URL=postgresql://u:p@banco-de-producao.invalid:5432/clama")
@AutoConfigureMockMvc
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class DatabaseUrlNaoVazaParaTestes {
	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void comDatabaseUrlOContextoAindaUsaOContainer() throws Exception {
		mvc.perform(get("/api/health/")).andExpect(jsonPath("$.database").value("ok"));
		assertThatCode(() -> BancoDeTeste.exigirContainer(jdbc)).doesNotThrowAnyException();
	}

	@Test
	void guardaRecusaBancoQueNaoEDoContainer() {
		for (String url : new String[] {"jdbc:postgresql://h.proxy.rlwy.net:12345/railway?sslmode=require",
				"jdbc:postgresql://localhost:5432/clama_backend", "jdbc:postgresql://evil/clama_testcontainers", null}) {
			assertThatThrownBy(() -> BancoDeTeste.exigirUrlDeContainer(url)).as(url)
					.isInstanceOf(IllegalStateException.class);
		}
		assertThatCode(() -> BancoDeTeste.exigirUrlDeContainer(
				"jdbc:postgresql://localhost:61234/clama_testcontainers?loggerLevel=OFF")).doesNotThrowAnyException();
	}
}
