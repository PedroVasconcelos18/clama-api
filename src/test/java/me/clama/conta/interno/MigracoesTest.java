package me.clama.conta.interno;

import static org.junit.jupiter.api.Assertions.assertEquals;

import me.clama.BancoDeTeste;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Flyway sobe do zero e o ddl-auto=validate (padrao do application.yml) aceita as entidades. */
@SpringBootTest
@Import(BancoDeTeste.class)
class MigracoesTest {

	@Autowired JdbcTemplate jdbc;

	@Test
	void tabelasCriadas() {
		var n = jdbc.queryForObject("""
				select count(*) from information_schema.tables where table_schema = 'public'
				and table_name in ('usuario','token_de_senha','auditoria','spring_session','spring_session_attributes')""",
				Integer.class);
		assertEquals(5, n);
	}

	@Test
	void planosTecnicosExistemERepetirAMigrationNaoDuplica() throws Exception {
		assertEquals(2, jdbc.queryForObject("select count(*) from plano where tipo <> 'COMUM'", Integer.class));
		var sql = new String(getClass().getResourceAsStream("/db/migration/V2__catalogo.sql").readAllBytes(),
				java.nio.charset.StandardCharsets.UTF_8);
		jdbc.execute(sql.substring(sql.indexOf("INSERT INTO plano")));
		assertEquals(2, jdbc.queryForObject("select count(*) from plano where tipo <> 'COMUM'", Integer.class));
		assertEquals(1000, jdbc.queryForObject("select min(ordem) from plano where tipo <> 'COMUM'", Integer.class));
	}
}
