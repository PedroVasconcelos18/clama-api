package me.clama.pagamento.interno;

import java.time.Clock;
import java.time.Duration;
import me.clama.infra.fila.TarefaRecorrente;
import me.clama.pagamento.interno.ProcessamentoDePagamento.Resultado;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * De hora em hora, reprocessa os eventos que ficaram ERRO porque o GET ao MP falhou ({@code mp_indisponivel}): o MP
 * reenvia o webhook por um tempo, mas desiste. Mesmo fluxo do webhook e do reprocessamento do admin.
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
	private final Clock relogio;

	ReprocessamentoDeEventosMp(EventosDePagamento eventos, ProcessamentoDePagamento processamento, Clock relogio) {
		super("reprocessar-eventos-mp", "0 7 * * * *");
		this.eventos = eventos;
		this.processamento = processamento;
		this.relogio = relogio;
	}

	@Override
	protected void executar(boolean dryRun) {
		var agora = relogio.instant();
		var pendentes = eventos.mpIndisponivel(agora.minus(ESPERA), agora.minus(JANELA), LIMITE);
		if (dryRun) {
			LOG.info("reprocessar-eventos-mp (dry-run): {} evento(s) seriam reprocessados", pendentes.size());
			return;
		}
		int feitos = 0;
		for (var e : pendentes) {
			feitos++;
			// ponytail: o MP ainda fora encerra a rodada (cada GET custa ate ~33 s); um evento que sempre falha sozinho
			// seguraria os mais novos ate sair da janela de 3 dias
			if (processamento.reprocessar(e.id(), e.tipo(), e.mpPagamentoId()) == Resultado.FALHA_TEMPORARIA) {
				LOG.warn("reprocessar-eventos-mp: MP ainda indisponivel, rodada encerrada (evento_id={})", e.id());
				break;
			}
		}
		LOG.info("reprocessar-eventos-mp: {} de {} evento(s) tentados", feitos, pendentes.size());
	}
}
