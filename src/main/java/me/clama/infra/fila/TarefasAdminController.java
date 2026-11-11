package me.clama.infra.fila;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import me.clama.infra.auditoria.Acao;
import me.clama.infra.auditoria.Auditoria;
import me.clama.infra.fila.Execucoes.Execucao;
import me.clama.infra.web.ErroDeNegocio;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tela de tarefas (D9, ADMIN pela cadeia padrao): o "rodou?" que o beat nunca deu. Junta os beans (nome, cron, modo), o
 * {@code scheduled_tasks} (proximo disparo, rodando agora, falhas seguidas) e a ultima execucao.
 */
@RestController
@RequestMapping("/api/admin/tarefas")
class TarefasAdminController {

	/** Passou do horario ha mais que isso e ninguem pegou: o no que dispara esta fora do ar ou a tarefa travou. */
	static final Duration TOLERANCIA = Duration.ofMinutes(5);

	/** {@code simulacao} ausente = {@code true}. */
	record Pedido(Boolean simulacao) {}

	record UltimaExecucao(Instant inicio, Instant fim, boolean ok, boolean simulacao, boolean manual, String resumo, String erro) {}

	record Tarefa(String nome, String cron, boolean simulacaoConfigurada, Instant proximaExecucao, boolean rodandoAgora,
			int falhasSeguidas, String situacao, UltimaExecucao ultima) {}

	record ExecucaoAdmin(long id, Instant inicio, Instant fim, boolean ok, boolean simulacao, boolean manual, Long autorId, String resumo,
			String erro) {}

	private record Agendada(Instant proxima, boolean rodando, int falhas) {}

	private final List<TarefaRecorrente> tarefas;
	private final JobRodarTarefa job;
	private final Fila fila;
	private final Execucoes execucoes;
	private final Auditoria auditoria;
	private final JdbcClient jdbc;
	private final Clock relogio;

	TarefasAdminController(List<TarefaRecorrente> tarefas, JobRodarTarefa job, Fila fila, Execucoes execucoes, Auditoria auditoria,
			JdbcClient jdbc, Clock relogio) {
		this.tarefas = tarefas.stream().sorted(java.util.Comparator.comparing(TarefaRecorrente::getName)).toList();
		this.job = job;
		this.fila = fila;
		this.execucoes = execucoes;
		this.auditoria = auditoria;
		this.jdbc = jdbc;
		this.relogio = relogio;
	}

	@GetMapping
	List<Tarefa> listarTarefas() {
		var agendadas = jdbc.sql("select task_name, execution_time, picked, consecutive_failures from scheduled_tasks where task_instance = 'recurring'")
				.query((rs, n) -> Map.entry(rs.getString("task_name"), new Agendada(rs.getObject("execution_time", java.time.OffsetDateTime.class).toInstant(),
						rs.getBoolean("picked"), rs.getInt("consecutive_failures"))))
				.list().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
		var ultimas = execucoes.ultimas().stream().collect(java.util.stream.Collectors.toMap(Execucao::tarefa, e -> e));
		var reais = execucoes.ultimasReais().stream().collect(java.util.stream.Collectors.toMap(Execucao::tarefa, e -> e));
		var agora = relogio.instant();
		return tarefas.stream().map(t -> {
			var a = agendadas.get(t.getName());
			var u = ultimas.get(t.getName());
			return new Tarefa(t.getName(), t.cron(), t.simulacaoConfigurada(), a == null ? null : a.proxima(), a != null && a.rodando(),
					a == null ? 0 : a.falhas(), situacao(a, reais.get(t.getName()), agora), u == null ? null
							: new UltimaExecucao(u.inicio(), u.fim(), u.ok(), u.simulacao(), u.manual(), u.resumo(), u.erro()));
		}).toList();
	}

	/**
	 * Sem linha = nao agendada; horario vencido e ninguem pegou = atrasada; senao a ultima execucao REAL decide: uma
	 * simulacao bem-sucedida do "Rodar agora" nao esconde uma falha de verdade ({@code ultima} na resposta mostra as duas).
	 */
	private static String situacao(Agendada a, Execucao ultima, Instant agora) {
		if (a == null) return "nao_agendada";
		if (!a.rodando() && a.proxima().isBefore(agora.minus(TOLERANCIA))) return "atrasada";
		return ultima != null && !ultima.ok() ? "falhou" : "ok";
	}

	/** Mais novas primeiro (ordem fixa). */
	@GetMapping("/{nome}/execucoes")
	PagedModel<ExecucaoAdmin> listarExecucoes(@PathVariable String nome, @ParameterObject @PageableDefault(size = 20) Pageable pagina) {
		existente(nome);
		var fixa = PageRequest.of(pagina.getPageNumber(), pagina.getPageSize(), Sort.unsorted());
		return new PagedModel<>(execucoes.listar(nome, fixa).map(e -> new ExecucaoAdmin(e.id(), e.inicio(), e.fim(), e.ok(), e.simulacao(),
				e.manual(), e.autorId(), e.resumo(), e.erro())));
	}

	/** 202: enfileira. Ja na fila (agendada ou rodando) = 409. A execucao real grava auditoria, na mesma transacao do enfileiramento. */
	@PostMapping("/{nome}/execucoes")
	@ResponseStatus(HttpStatus.ACCEPTED)
	@Transactional
	void rodarTarefa(@PathVariable String nome, @RequestBody(required = false) Pedido corpo, Authentication auth) {
		existente(nome);
		var simulacao = corpo == null || corpo.simulacao() == null || corpo.simulacao();
		var autor = Long.valueOf(auth.getName());
		if (!fila.enfileirar(job, job.instancia(nome), new JobRodarTarefa.Disparo(nome, simulacao, autor), relogio.instant())) {
			throw new ErroDeNegocio(HttpStatus.CONFLICT, "tarefa_na_fila", "Tarefa já está na fila.", "Essa tarefa já está na fila para rodar.");
		}
		if (!simulacao) auditoria.registrar(autor, "tarefa", nome, Acao.ADICAO, Map.of("execucao", "real"));
	}

	private void existente(String nome) {
		if (tarefas.stream().noneMatch(t -> t.getName().equals(nome))) {
			throw new ErroDeNegocio(HttpStatus.NOT_FOUND, "tarefa_inexistente", "Tarefa inexistente.", "Essa tarefa não existe.");
		}
	}
}
