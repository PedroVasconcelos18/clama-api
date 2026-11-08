package me.clama.api.comum.fila;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.serializer.Serializer;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.FailureHandler;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import me.clama.api.comum.observabilidade.SentryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import tools.jackson.databind.ObjectMapper;

/** Fila persistente no Postgres (db-scheduler core; o starter 17.0.0 e so para Boot 3). */
@Configuration(proxyBeanMethods = false)
public class FilaConfig {
	private static final Logger LOG = LoggerFactory.getLogger(FilaConfig.class);
	/**
	 * Redeploy no Railway: o db-scheduler espera isto, interrompe e espera de novo (ate 2x). Com o Tomcat (15 s) antes e
	 * a agenda (10 s) depois: 15 + 2x15 + 10 = 55 s, dentro do draining de 60 s (Etapa 9, ensaio do deploy).
	 */
	static final Duration ESPERA_NO_SHUTDOWN = Duration.ofSeconds(15);

	/** Unico observador: loga e manda pro Sentry (que e no-op sem DSN). */
	@Bean
	ObservadorDeFalhaDeJob observadorDeFalhaDeJob() {
		return (tipo, instancia, erro) -> {
			LOG.error("Job {} ({}) falhou definitivamente", tipo, instancia, erro);
			SentryConfig.capturar("job", tipo, Map.of("job_instancia", instancia), erro);
		};
	}

	@Bean
	Serializer serializadorJson(ObjectMapper json) {
		return new Serializer() {
			@Override
			public byte[] serialize(Object data) {
				return json.writeValueAsBytes(data);
			}

			@Override
			public <T> T deserialize(Class<T> clazz, byte[] bytes) {
				return json.readValue(bytes, clazz);
			}
		};
	}

	@Bean
	TarefasDaFila tarefasDaFila(ObjectProvider<TipoDeJob<?>> tipos, ObservadorDeFalhaDeJob observador) {
		Map<String, OneTimeTask<?>> m = new LinkedHashMap<>();
		tipos.orderedStream().forEach(t -> {
			if (m.put(t.nome(), tarefa(t, observador)) != null) {
				throw new IllegalStateException("TipoDeJob duplicado: ja existe um job com nome '" + t.nome() + "'");
			}
		});
		return new TarefasDaFila(m);
	}

	private static <P> OneTimeTask<P> tarefa(TipoDeJob<P> t, ObservadorDeFalhaDeJob observador) {
		FailureHandler<P> falha = FailureHandler.<P>maxRetries(t.maxRetentativas())
				.withBackoff(t.esperaEntreTentativas(), t.fatorDeBackoff())
				.thenRemove(c -> desistiu(t, c, observador));
		return new OneTimeTask<>(t.nome(), t.payload(), falha) {
			@Override
			public void executeOnce(TaskInstance<P> instance, ExecutionContext ctx) {
				try {
					t.executar(instance.getData(), ctx.getExecution().consecutiveFailures);
				} catch (RuntimeException e) {
					throw e;
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			}
		};
	}

	/** A linha ja foi removida: nada aqui faz o job rodar de novo. */
	@SuppressWarnings("unchecked")
	static <P> void desistiu(TipoDeJob<P> t, ExecutionComplete c, ObservadorDeFalhaDeJob observador) {
		Throwable erro = c.getCause().orElse(null);
		TaskInstance<P> instancia = (TaskInstance<P>) c.getExecution().taskInstance;
		try {
			t.esgotou(instancia.getData(), erro);
		} catch (RuntimeException e) {
			LOG.error("Job {} ({}): esgotou() falhou", t.nome(), instancia.getId(), e);
		}
		observador.falhou(t.nome(), instancia.getId(), erro);
	}

	/** Usa a conexao da transacao Spring corrente: o INSERT entra no commit/rollback do chamador. */
	@Bean
	@Primary
	SchedulerClient schedulerClient(DataSource ds, TarefasDaFila tarefas, Serializer serializer) {
		return SchedulerClient.Builder.create(new TransactionAwareDataSourceProxy(ds), List.<Task<?>>copyOf(tarefas.porNome().values()))
				.serializer(serializer)
				.build();
	}

	// so inicia o polling depois do Flyway: na 1a subida em prod scheduled_tasks ainda nao existe
	// desligado nos testes: contextos em cache dividem o mesmo scheduled_tasks e um roubaria o job do outro
	@ConditionalOnProperty(name = "clama.fila.scheduler-ativo", havingValue = "true", matchIfMissing = true)
	@DependsOnDatabaseInitialization
	// ponytail: uma instancia e 4 threads; aumentar se a fila acumular
	@Bean(initMethod = "start", destroyMethod = "stop")
	Scheduler scheduler(DataSource ds, TarefasDaFila tarefas, Serializer serializer,
			@Value("${clama.fila.polling}") Duration polling, @Value("${clama.fila.heartbeat}") Duration heartbeat) {
		return Scheduler.create(ds, List.<Task<?>>copyOf(tarefas.porNome().values()))
				.threads(4)
				.pollingInterval(polling)
				.heartbeatInterval(heartbeat) // 6 heartbeats perdidos (default) = execucao morta, retomada por outra instancia
				.shutdownMaxWait(ESPERA_NO_SHUTDOWN)
				.serializer(serializer)
				.build();
	}
}
