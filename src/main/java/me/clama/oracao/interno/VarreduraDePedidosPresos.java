package me.clama.oracao.interno;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import me.clama.infra.fila.Fila;
import me.clama.infra.fila.TarefaRecorrente;
import me.clama.infra.fila.TipoDeJob;
import me.clama.pedido.PedidoApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code pedidos-presos} (INV:113), a cada 10 min: pedido em andamento sem mudar ha mais de 20 min e <b>sem job na
 * fila</b> (gerar, enviar ou reenviar) vai a ERRO {@code pedido_preso}, com alerta. Nunca regera. O
 * {@code falharSeParado} confere de novo com a linha travada: quem andou no meio nao e marcado.
 */
@Component
class VarreduraDePedidosPresos extends TarefaRecorrente {

	private static final Logger LOG = LoggerFactory.getLogger(VarreduraDePedidosPresos.class);
	static final Duration JANELA = Duration.ofMinutes(20);

	private final PedidoApi pedidos;
	private final Fila fila;
	private final List<TipoDeJob<?>> jobs;
	private final JobAlertaAdmin alerta;
	private final TransactionTemplate tx;
	private final Clock relogio;

	VarreduraDePedidosPresos(PedidoApi pedidos, @Lazy Fila fila, JobGerarOracao gerar, JobEnviarOracao enviar,
			JobReenviarOracao reenviar, JobAlertaAdmin alerta, TransactionTemplate tx, Clock relogio) {
		super("pedidos-presos", "0 */10 * * * *");
		this.pedidos = pedidos;
		this.fila = fila;
		this.jobs = List.of(gerar, enviar, reenviar);
		this.alerta = alerta;
		this.tx = tx;
		this.relogio = relogio;
	}

	@Override
	protected void executar(boolean dryRun) {
		var antesDe = relogio.instant().minus(JANELA); // antes da lista: o corte do falharSeParado nunca e mais novo
		int presos = 0;
		int marcados = 0;
		for (var id : pedidos.emAndamentoParados(JANELA)) {
			if (comJob(id)) continue;
			presos++;
			if (dryRun) {
				LOG.info("pedidos-presos (dry-run): pedido {} seria marcado como preso", id);
			} else if (Boolean.TRUE.equals(tx.execute(s -> marcar(id, antesDe)))) {
				marcados++;
				LOG.warn("pedidos-presos: pedido {} parado ha mais de {} min sem job, foi para ERRO", id, JANELA.toMinutes());
			}
		}
		LOG.info("pedidos-presos: {} preso(s), {} marcado(s){}", presos, marcados, dryRun ? " (dry-run)" : "");
	}

	/** ERRO e alerta na mesma transacao; {@code false} = andou no meio, nada feito. */
	private boolean marcar(UUID id, java.time.Instant antesDe) {
		if (!pedidos.falharSeParado(id, antesDe, "pedido_preso")) return false;
		alerta.enfileirar(id, "pedido_preso");
		return true;
	}

	/** Agendado, em retentativa ou em execucao (linha picked) em outro no. */
	private boolean comJob(UUID id) {
		return jobs.stream().anyMatch(j -> fila.existePendente(j.getName(), j.instancia(id)));
	}
}
