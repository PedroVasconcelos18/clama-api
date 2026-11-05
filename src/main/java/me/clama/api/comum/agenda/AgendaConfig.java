package me.clama.api.comum.agenda;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.clama.api.comum.agenda.TarefaAgendada.ResultadoDaTarefa;
import me.clama.api.comum.observabilidade.SentryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

/** Agenda cada TarefaAgendada por clama.agenda.&lt;nome&gt;.cron / .modo, no fuso de Sao Paulo. */
@Configuration(proxyBeanMethods = false)
public class AgendaConfig implements SmartInitializingSingleton {
	private static final Logger LOG = LoggerFactory.getLogger(AgendaConfig.class);
	static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");

	private final TaskScheduler scheduler;
	private final Map<String, TarefaAgendada> tarefas = new LinkedHashMap<>();
	private final Map<String, String> crons = new LinkedHashMap<>();
	private final Map<String, ModoDeExecucao> modos = new LinkedHashMap<>();

	@Bean
	static ThreadPoolTaskScheduler agendaScheduler() {
		var s = new ThreadPoolTaskScheduler();
		s.setPoolSize(2);
		s.setThreadNamePrefix("agenda-");
		return s;
	}

	/** Falha rapido: sem cron ou modo invalido a aplicacao nao sobe. */
	public AgendaConfig(List<TarefaAgendada> lista, Environment env, @Qualifier("agendaScheduler") TaskScheduler scheduler) {
		this.scheduler = scheduler;
		for (TarefaAgendada t : lista) {
			String n = t.nome();
			if (tarefas.put(n, t) != null) {
				throw new IllegalStateException("TarefaAgendada duplicada: '" + n + "'");
			}
			String cron = env.getProperty("clama.agenda." + n + ".cron");
			if (cron == null || cron.isBlank()) {
				throw new IllegalStateException("Tarefa agendada '" + n + "' sem cron: defina clama.agenda." + n + ".cron");
			}
			crons.put(n, cron);
			String modo = env.getProperty("clama.agenda." + n + ".modo");
			try {
				modos.put(n, modo == null || modo.isBlank()
						? (t.alteraDados() ? ModoDeExecucao.LISTAR : ModoDeExecucao.EXECUTAR)
						: ModoDeExecucao.valueOf(modo.trim().toUpperCase()));
			} catch (IllegalArgumentException e) {
				throw new IllegalStateException("Tarefa agendada '" + n + "': clama.agenda." + n + ".modo invalido '" + modo
						+ "' (use LISTAR, EXECUTAR ou DESLIGADA)", e);
			}
			new CronTrigger(cron, FUSO); // cron invalido estoura aqui
		}
	}

	@Override
	public void afterSingletonsInstantiated() {
		tarefas.forEach((n, t) -> {
			ModoDeExecucao modo = modos.get(n);
			LOG.info("Tarefa agendada '{}': cron '{}' (America/Sao_Paulo), modo {}", n, crons.get(n), modo);
			if (modo != ModoDeExecucao.DESLIGADA) {
				scheduler.schedule(() -> rodarAgendada(t, modo), new CronTrigger(crons.get(n), FUSO));
			}
		});
	}

	/** Roda uma tarefa uma vez, a mao (comando do jar). Erros propagam. */
	public ResultadoDaTarefa executarAgora(String nome, ModoDeExecucao modo) {
		TarefaAgendada t = tarefas.get(nome);
		if (t == null) {
			throw new IllegalArgumentException("Tarefa agendada desconhecida: '" + nome + "'");
		}
		ResultadoDaTarefa r = t.executar(modo);
		LOG.info("Tarefa agendada concluida: {}", r);
		return r;
	}

	private void rodarAgendada(TarefaAgendada t, ModoDeExecucao modo) {
		try {
			executarAgora(t.nome(), modo);
		} catch (Exception e) {
			falhou(t.nome(), e);
		}
	}

	/** Unico ponto de falha de tarefa agendada. */
	void falhou(String nome, Exception e) {
		LOG.error("Tarefa agendada '{}' falhou", nome, e);
		SentryConfig.capturar("tarefa_agendada", nome, e);
	}
}
