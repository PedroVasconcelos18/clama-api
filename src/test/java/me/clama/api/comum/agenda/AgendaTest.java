package me.clama.api.comum.agenda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.clama.api.comum.agenda.TarefaAgendada.ResultadoDaTarefa;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.CronExpression;

class AgendaTest {
	static class Fake implements TarefaAgendada {
		final boolean altera;
		final boolean falha;
		final List<ModoDeExecucao> modos = new ArrayList<>();

		Fake(boolean altera, boolean falha) {
			this.altera = altera;
			this.falha = falha;
		}

		public String nome() { return "x"; }
		public boolean alteraDados() { return altera; }

		public ResultadoDaTarefa executar(ModoDeExecucao modo) {
			modos.add(modo);
			if (falha) throw new IllegalStateException("boom");
			return new ResultadoDaTarefa("x", modo, Map.of("n", 1));
		}
	}

	private final TaskScheduler scheduler = mock(TaskScheduler.class);

	/** Monta a agenda, agenda e devolve o Runnable que o scheduler recebeu (null se nao agendou). */
	private Runnable agendar(Fake t, MockEnvironment env) {
		new AgendaConfig(List.of(t), env, scheduler).afterSingletonsInstantiated();
		ArgumentCaptor<Runnable> r = ArgumentCaptor.forClass(Runnable.class);
		try {
			verify(scheduler).schedule(r.capture(), any(Trigger.class));
		} catch (AssertionError e) {
			return null;
		}
		return r.getValue();
	}

	private static MockEnvironment env() {
		return new MockEnvironment().withProperty("clama.agenda.x.cron", "* * * * * *");
	}

	@Test
	void alteraDadosSemModoRodaEmListar() {
		var t = new Fake(true, false);
		agendar(t, env()).run();
		assertThat(t.modos).containsExactly(ModoDeExecucao.LISTAR);
	}

	@Test
	void naoAlteraDadosRodaEmExecutar() {
		var t = new Fake(false, false);
		agendar(t, env()).run();
		assertThat(t.modos).containsExactly(ModoDeExecucao.EXECUTAR);
	}

	@Test
	void modoConfiguradoVence() {
		var t = new Fake(true, false);
		agendar(t, env().withProperty("clama.agenda.x.modo", "EXECUTAR")).run();
		assertThat(t.modos).containsExactly(ModoDeExecucao.EXECUTAR);
	}

	@Test
	void desligadaNaoRoda() {
		var t = new Fake(false, false);
		assertThat(agendar(t, env().withProperty("clama.agenda.x.modo", "DESLIGADA"))).isNull();
		verify(scheduler, never()).schedule(any(Runnable.class), any(Trigger.class));
		assertThat(t.modos).isEmpty();
	}

	@Test
	void semCronNaoSobe() {
		assertThatThrownBy(() -> new AgendaConfig(List.of(new Fake(true, false)), new MockEnvironment(), scheduler))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("'x'")
				.hasMessageContaining("clama.agenda.x.cron");
	}

	@Test
	void cronUsaFusoDeSaoPaulo() {
		var cron = CronExpression.parse("0 0 4 * * *");
		var proxima = cron.next(ZonedDateTime.of(2026, 11, 5, 12, 0, 0, 0, AgendaConfig.FUSO));
		assertThat(proxima.getHour()).isEqualTo(4);
		assertThat(proxima.getZone()).isEqualTo(ZoneId.of("America/Sao_Paulo"));
		assertThat(proxima.toInstant()).isEqualTo(ZonedDateTime.of(2026, 11, 6, 7, 0, 0, 0, ZoneId.of("UTC")).toInstant());
	}

	@Test
	void excecaoNaoDesagenda() {
		var t = new Fake(false, true);
		Runnable r = agendar(t, env());
		r.run();
		r.run(); // nao propaga e continua executavel
		assertThat(t.modos).hasSize(2);
	}
}
