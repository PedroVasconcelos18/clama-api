package me.clama.infra.fila;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.Scheduler;
import me.clama.BancoDeTeste;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Nos testes a fila fica parada (clama.fila.iniciar=false): contextos em cache nao roubam jobs uns dos outros. */
@SpringBootTest
@Import(BancoDeTeste.class)
class FilaParadaTest {

	@Test
	void schedulerExisteMasNaoInicia(@Autowired Scheduler scheduler) {
		assertThat(scheduler.getSchedulerState().isStarted()).isFalse();
	}
}
