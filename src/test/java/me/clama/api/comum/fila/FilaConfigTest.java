package me.clama.api.comum.fila;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

class FilaConfigTest {
	record P(String id) {}

	static TipoDeJob<P> tipo(String nome) {
		return new TipoDeJob<>() {
			public String nome() { return nome; }
			public Class<P> payload() { return P.class; }
			public Duration esperaEntreTentativas() { return Duration.ofSeconds(30); }
			public void executar(P p) {}
		};
	}

	@Test
	@SuppressWarnings("unchecked")
	void nomeDuplicadoNaoSobe() {
		ObjectProvider<TipoDeJob<?>> tipos = mock(ObjectProvider.class);
		when(tipos.orderedStream()).thenReturn(Stream.of(tipo("oracao"), tipo("oracao")));
		assertThatThrownBy(() -> new FilaConfig().tarefasDaFila(tipos, (n, i, e) -> {}))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("oracao");
	}

	@Test
	void esgotouQueEstouraNaoImpedeOObservadorECausaNulaPassaAdiante() {
		List<Object> esgotou = new ArrayList<>();
		List<Object> observou = new ArrayList<>();
		TipoDeJob<P> t = new TipoDeJob<>() {
			public String nome() { return "j"; }
			public Class<P> payload() { return P.class; }
			public Duration esperaEntreTentativas() { return Duration.ofSeconds(30); }
			public void executar(P p) {}
			public void esgotou(P p, Throwable erro) {
				esgotou.add(p);
				esgotou.add(erro);
				throw new IllegalStateException("esgotou quebrou");
			}
		};
		var exec = new Execution(Instant.now(), new TaskInstance<>("j", "inst-1", new P("42")));
		FilaConfig.desistiu(t, ExecutionComplete.failure(exec, Instant.now(), Instant.now(), null),
				(tipo, instancia, erro) -> observou.addAll(java.util.Arrays.asList(tipo, instancia, erro)));
		assertThat(esgotou).containsExactly(new P("42"), null);
		assertThat(observou).containsExactly("j", "inst-1", null);
	}

	@Test
	void heartbeatDeProducaoE20s() throws Exception {
		var yml = new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yml")).get(0);
		assertThat(yml.getProperty("clama.fila.heartbeat")).hasToString("20s");
	}
}
