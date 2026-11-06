package me.clama.api.comum.fila;

import static org.assertj.core.api.Assertions.assertThat;

import me.clama.api.BancoDeTeste;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** O scheduler (initMethod start) so pode iniciar depois do Flyway: scheduled_tasks nasce na migration V1. */
@SpringBootTest(properties = "clama.fila.scheduler-ativo=true")
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
// scheduler ligado: parar no fim, senao o contexto em cache executa os jobs (gerar/enviar/alerta) dos outros testes
@org.springframework.test.annotation.DirtiesContext
class FilaOrdemTest {
	@Autowired ConfigurableApplicationContext ctx;

	@Test
	void schedulerDependeDoFlyway() {
		var bf = ctx.getBeanFactory();
		String flyway = bf.getBeanNamesForType(FlywayMigrationInitializer.class)[0];
		assertThat(bf.getDependenciesForBean("scheduler")).contains(flyway);
	}
}
