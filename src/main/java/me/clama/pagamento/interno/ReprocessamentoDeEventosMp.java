package me.clama.pagamento.interno;

import java.time.Clock;
import java.time.Duration;
import me.clama.infra.alerta.AlertaAoAdmin;
import me.clama.infra.email.EnvioAssincrono;
import me.clama.infra.fila.TarefaRecorrente;
import me.clama.pagamento.interno.ProcessamentoDePagamento.Resultado;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * De hora em hora, reprocessa os eventos que ficaram ERRO porque o GET ao MP falhou ({@code mp_indisponivel}): o MP
 * reenvia o webhook por um tempo, mas desiste. Mesmo fluxo do webhook e do reprocessamento do admin. O que passa de 3 dias
 * sem resposta vira {@code mp_indisponivel_expirado}, com um alerta ao admin, e fica para a tela de eventos.
 */
@Component
class ReprocessamentoDeEventosMp extends TarefaRecorrente {

	private static final Logger LOG = LoggerFactory.getLogger(ReprocessamentoDeEventosMp.class);
	static final int LIMITE = 50;
	/** Da tempo ao reenvio do proprio MP antes de tentar de novo. */
	static final Duration ESPERA = Duration.ofMinutes(10);
	/** Mais velho que isso o admin resolve pela tela de eventos. */
	static final Duration JANELA = Duration.ofDays(3);

	private final EventosDePagamento eventos;
	private final ProcessamentoDePagamento processamento;
	private final AlertaAoAdmin alerta;
	private final Clock relogio;

	ReprocessamentoDeEventosMp(EventosDePagamento eventos, ProcessamentoDePagamento processamento, AlertaAoAdmin alerta,
			Clock relogio) {
		super("reprocessar-eventos-mp", "0 7 * * * *");
		this.eventos = eventos;
		this.processamento = processamento;
		this.alerta = alerta;
		this.relogio = relogio;
	}

	@Override
	protected String executar(boolean dryRun) {
		var agora = relogio.instant();
		var pendentes = eventos.mpIndisponivel(agora.minus(ESPERA), agora.minus(JANELA), LIMITE);
		if (dryRun) {
			LOG.info("reprocessar-eventos-mp (dry-run): {} evento(s) seriam reprocessados", pendentes.size());
			return pendentes.size() + " evento(s) seriam reprocessados (simulação)";
		}
		expirar(agora);
		int feitos = 0;
		for (var e : pendentes) {
			feitos++;
			// ponytail: a 1a falha temporaria encerra a rodada (cada GET com o MP fora custa ate ~33 s). Quem falhou vai para o
			// fim da fila (atualizado_em), entao um evento que sempre falha atrasa os outros em no maximo uma rodada
			if (processamento.reprocessar(e.id(), e.tipo(), e.mpPagamentoId()) == Resultado.FALHA_TEMPORARIA) {
				if ("mp_indisponivel".equals(eventos.motivo(e.id()))) {
					LOG.warn("reprocessar-eventos-mp: MP ainda indisponivel, rodada encerrada (evento_id={})", e.id());
				} else {
					LOG.warn("reprocessar-eventos-mp: falha transitoria do banco, rodada encerrada (evento_id={})", e.id());
				}
				break;
			}
		}
		LOG.info("reprocessar-eventos-mp: {} de {} evento(s) tentados", feitos, pendentes.size());
		return feitos + " de " + pendentes.size() + " evento(s) tentados";
	}

	/** Uma vez por evento: a marca muda o motivo, e a proxima rodada nao o acha mais. */
	private void expirar(java.time.Instant agora) {
		for (var e : eventos.expirarMpIndisponivel(agora.minus(JANELA), agora)) {
			LOG.warn("reprocessar-eventos-mp: evento {} passou de 3 dias sem resposta do MP", e.id());
			try {
				alerta.enviar(new AlertaAoAdmin.Alerta("mp_indisponivel_expirado", "O evento " + e.id() + " (pagamento "
						+ e.mpPagamentoId() + ") ficou 3 dias sem resposta do Mercado Pago. Reprocesse pela tela de eventos.",
						null, null, null, null), "pagamento/evento/" + e.id() + "/expirado");
			} catch (RuntimeException ex) {
				EnvioAssincrono.registrar("alerta ao admin do evento expirado " + e.id(), ex);
			}
		}
	}
}
