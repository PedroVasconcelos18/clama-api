package me.clama.infra.email;

import io.sentry.Sentry;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Executor pequeno e dedicado (2 threads, fila de 100) para trabalho que nao pode atrasar a resposta nem denunciar
 * nada pelo tempo. Fila cheia ou falha da tarefa: ERROR no log e no Sentry; quem chamou nao fica sabendo.
 * Propositalmente nao e um bean {@code Executor}: isso desligaria o executor padrao do Boot.
 */
@Component
public class EnvioAssincrono {

	private static final Logger LOG = LoggerFactory.getLogger(EnvioAssincrono.class);

	private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
			new ArrayBlockingQueue<>(100), r -> {
				var t = new Thread(r, "envio-de-email");
				t.setDaemon(true);
				return t;
			});
	private final AtomicInteger pendentes = new AtomicInteger();

	public void executar(String descricao, Runnable tarefa) {
		pendentes.incrementAndGet();
		try {
			executor.execute(() -> {
				try {
					tarefa.run();
				} catch (RuntimeException e) {
					registrar(descricao, e);
				} finally {
					pendentes.decrementAndGet();
				}
			});
		} catch (RejectedExecutionException e) {
			pendentes.decrementAndGet();
			registrar("fila cheia: " + descricao, e);
		}
	}

	/** Tarefas na fila ou rodando (os testes esperam isto zerar). */
	public int pendentes() { return pendentes.get(); }

	/** Falha que nao muda a resposta: ERROR no log e no Sentry. */
	public static void registrar(String descricao, Exception e) {
		LOG.error("Falha: {}", descricao, e);
		Sentry.captureException(e);
	}

	@PreDestroy
	void encerrar() {
		executor.shutdown();
	}
}
