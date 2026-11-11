package me.clama.pagamento.interno;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import me.clama.catalogo.CatalogoApi;
import me.clama.infra.alerta.AlertaAoAdmin;
import me.clama.infra.email.EnvioAssincrono;
import me.clama.pedido.PedidoApi;
import me.clama.pedido.StatusPedido;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

/**
 * Uma notificacao do Mercado Pago depois da assinatura (mapa §1.5, D8, D9). O estado vem sempre do GET do pagamento,
 * nunca do corpo, e o GET roda sem transacao aberta. Depois, uma transacao curta com o pedido travado decide e grava
 * tudo junto: pagamento, conta, repasse e a marca do evento. Quem chega depois a trava acha o pedido pago.
 *
 * <p>Nenhum ramo de negocio lanca dentro da transacao (lancar a deixaria rollback-only e levaria a marca IGNORADO junto):
 * o pedido inexistente e conferido antes. Excecao inesperada desfaz tudo e o evento vira ERRO fora dela.
 */
@Component
public class ProcessamentoDePagamento {

	private static final Logger LOG = LoggerFactory.getLogger(ProcessamentoDePagamento.class);
	/** 4xx do GET tratados como 5xx: 408/429 passam, 401/403 se corrigem com configuracao. Ignorar perderia o pagamento. */
	static final Set<Integer> TRANSIENTES = Set.of(401, 403, 408, 429);
	private static final Set<String> ESTORNOS = Set.of("refunded", "charged_back");

	/** Desfecho; {@code status} e o corpo {@code {"status": ...}} do webhook (respostas do sistema antigo, mais "error"). */
	public enum Resultado {
		OK("ok"), IGNORADO("ignored"), JA_PAGO("already_paid"), JA_PROCESSADO("already_processed"),
		/** Falha que nao se resolve sozinha: evento ERRO e alerta; respondida 200 para o MP nao reenviar em loop. */
		ERRO("error"),
		/** MP fora, rede, trava ou banco: evento ERRO; respondida com 5xx para o MP reenviar. */
		FALHA_TEMPORARIA(null);

		public final String status;

		Resultado(String status) {
			this.status = status;
		}
	}

	/** O que a transacao decidiu; {@code alerta} (motivo) e {@code detalhe} saem depois do commit. */
	private record Decisao(Resultado resultado, String alerta, String detalhe) {
		static Decisao de(Resultado r) {
			return new Decisao(r, null, null);
		}
	}

	private final EventosDePagamento eventos;
	private final MercadoPago mercadoPago;
	private final PedidoApi pedidos;
	private final Cobrancas cobrancas;
	private final Repasses repasses;
	private final CatalogoApi catalogo;
	private final AlertaAoAdmin alerta;
	private final TransactionTemplate tx;
	private final Clock relogio;

	ProcessamentoDePagamento(EventosDePagamento eventos, MercadoPago mercadoPago, PedidoApi pedidos, Cobrancas cobrancas,
			Repasses repasses, CatalogoApi catalogo, AlertaAoAdmin alerta, TransactionTemplate tx, Clock relogio) {
		this.eventos = eventos;
		this.mercadoPago = mercadoPago;
		this.pedidos = pedidos;
		this.cobrancas = cobrancas;
		this.repasses = repasses;
		this.catalogo = catalogo;
		this.alerta = alerta;
		this.tx = tx;
		this.relogio = relogio;
	}

	/** Notificacao com assinatura ja conferida. {@code dataId}: o {@code data.id} assinado (da query). */
	public Resultado receber(String idExterno, String tipo, String dataId, String payloadJson) {
		var evento = eventos.registrar(idExterno, tipo, dataId, payloadJson, relogio.instant());
		if (evento.terminal()) {
			LOG.info("Mercado Pago webhook: ja processado (event=mercadopago_webhook_duplicate, id_externo={})", idExterno);
			return Resultado.JA_PROCESSADO;
		}
		if (!"payment".equals(tipo)) return ignorar(evento.id(), "tipo_nao_payment");
		return processar(evento.id(), dataId);
	}

	/** Evento RECEBIDO ou ERRO; o reprocessamento do admin (T6) entra por aqui. Nunca dentro de uma transacao. */
	public Resultado processar(long eventoId, String dataId) {
		// o GET ao MP nunca roda com transacao aberta: e o que sustenta o exactly-once do dinheiro
		Assert.state(!TransactionSynchronizationManager.isActualTransactionActive(), "processar abre as proprias transacoes");
		long mpId;
		try {
			mpId = Long.parseLong(dataId);
		} catch (NumberFormatException e) {
			return ignorar(eventoId, "data_id_invalido");
		}
		MercadoPago.Pagamento pagamento;
		try {
			pagamento = mercadoPago.pagamento(mpId);
		} catch (MercadoPago.Falha f) {
			if (f.quatroXX() && !TRANSIENTES.contains(f.status())) {
				// pagamento inexistente ou requisicao recusada: nao e transiente, 200 sem loop de reenvio
				Sentry.captureMessage("Mercado Pago webhook: GET do pagamento deu " + f.status(), SentryLevel.ERROR);
				return ignorar(eventoId, "mp_" + f.status());
			}
			eventos.marcar(eventoId, EventosDePagamento.ERRO, "mp_indisponivel", null, relogio.instant());
			LOG.warn("Mercado Pago webhook: GET do pagamento falhou (event=mercadopago_webhook_fetch_failed, mp_pagamento_id={}, "
					+ "status={})", mpId, f.status());
			return Resultado.FALHA_TEMPORARIA;
		}
		var status = pagamento.status() == null ? "" : pagamento.status();
		var aprovado = "approved".equals(status);
		if (!aprovado && !ESTORNOS.contains(status)) return ignorar(eventoId, "nao_aprovado:" + status);
		var pedidoId = uuid(pagamento.referencia());
		// antes da transacao: dentro dela o "nao encontrado" lancaria e a marca de IGNORADO voltaria junto
		if (pedidoId == null || pedidos.resumo(pedidoId).isEmpty()) return ignorar(eventoId, "pedido_inexistente");

		Decisao d;
		try {
			d = tx.execute(s -> aprovado ? aprovar(eventoId, pedidoId, mpId, pagamento) : estornar(eventoId, pedidoId, mpId, status));
		} catch (RuntimeException e) {
			// a transacao ja voltou inteira (pedido, conta, repasse); a marca vai em autocommit, fora dela
			eventos.marcar(eventoId, EventosDePagamento.ERRO, "erro_interno", pedidoId, relogio.instant());
			Sentry.captureException(e);
			LOG.error("Mercado Pago webhook: falha no processamento (event=mercadopago_webhook_error, evento_id={}, pedido_id={})",
					eventoId, pedidoId, e);
			if (transitoria(e)) return Resultado.FALHA_TEMPORARIA;
			alertar(pedidoId, mpId, "erro_interno", "O pagamento " + mpId + " (" + status + ") não pôde ser processado. "
					+ "Corrija a causa (ver Sentry) e reprocesse o evento.");
			return Resultado.ERRO;
		}
		if (d.alerta() != null) alertar(pedidoId, mpId, d.alerta(), d.detalhe());
		LOG.info("Mercado Pago webhook: {} (event=mercadopago_webhook_processed, evento_id={}, pedido_id={}, mp_pagamento_id={})",
				d.resultado().status, eventoId, pedidoId, mpId);
		return d.resultado();
	}

	private Decisao aprovar(long eventoId, UUID pedidoId, long mpId, MercadoPago.Pagamento pagamento) {
		pedidos.travarParaCobranca(pedidoId); // ate o commit; o resumo abaixo ja le a linha travada
		var p = pedidos.resumo(pedidoId).orElseThrow();
		if (!cobrancas.ehCobrancaDoPedido(pedidoId, mpId)) {
			return ignorado(eventoId, pedidoId, "cobranca_desconhecida", "O pagamento " + mpId
					+ " foi aprovado com a referência deste pedido, mas não é de nenhuma cobrança dele.");
		}
		if (p.status() != StatusPedido.AGUARDANDO_PAGAMENTO) {
			if (cobrancas.pagouOPedido(pedidoId, mpId)) { // outra notificacao do pagamento que ja pagou
				return Decisao.de(marcar(eventoId, EventosDePagamento.IGNORADO, "ja_pago", pedidoId) ? Resultado.JA_PAGO
						: Resultado.JA_PROCESSADO);
			}
			return ignorado(eventoId, pedidoId, "pagamento_orfao", "O pagamento " + mpId + " foi aprovado com o pedido já fora "
					+ "de aguardando pagamento. Estorne pelo painel do Mercado Pago.");
		}
		var esperado = BigDecimal.valueOf(p.valorCentavos(), 2);
		if (pagamento.valor() == null || pagamento.valor().compareTo(esperado) != 0 || !"BRL".equals(pagamento.moeda())) {
			Sentry.captureMessage("Mercado Pago webhook: valor ou moeda divergente no pagamento " + mpId, SentryLevel.ERROR);
			return ignorado(eventoId, pedidoId, "valor_divergente", "O pagamento " + mpId + " foi aprovado com " + pagamento.valor()
					+ " " + pagamento.moeda() + "; o pedido é de " + esperado + " BRL. O pedido não foi marcado como pago.");
		}
		// sob a trava o pedido ainda aguarda: confirma, vincula ou cria a conta e pede a geracao da oracao
		if (!pedidos.confirmarPagamento(pedidoId)) throw new IllegalStateException("pedido mudou de status sob a trava");
		var agora = relogio.instant();
		cobrancas.registrarPagamento(mpId, agora);
		repasses.registrar(pedidoId, p.instituicaoId(), p.valorCentavos(), agora);
		if (!eventos.marcar(eventoId, EventosDePagamento.PROCESSADO, null, pedidoId, agora)) {
			LOG.warn("Mercado Pago webhook: evento ja terminal ao marcar processado (evento_id={})", eventoId);
		}
		return Decisao.de(Resultado.OK);
	}

	/** D9: o pedido nao muda; o repasse REGISTRADO e cancelado, o ja TRANSFERIDO vira alerta de acerto. */
	private Decisao estornar(long eventoId, UUID pedidoId, long mpId, String status) {
		pedidos.travarParaCobranca(pedidoId); // serializa com uma aprovacao do mesmo pedido em andamento
		if (!cobrancas.pagouOPedido(pedidoId, mpId)) return ignorado(eventoId, pedidoId, "estorno_sem_pagamento", null);
		var desfecho = repasses.cancelar(pedidoId, status, relogio.instant());
		if (!marcar(eventoId, EventosDePagamento.PROCESSADO, desfecho, pedidoId)) return Decisao.de(Resultado.JA_PROCESSADO);
		if (!Repasses.TRANSFERIDO.equals(desfecho)) return Decisao.de(Resultado.OK);
		return new Decisao(Resultado.OK, "estorno_repasse_transferido", "O pagamento " + mpId + " teve " + status
				+ ", mas o repasse de " + AlertaAoAdmin.reais(repasses.valorCentavos(pedidoId))
				+ " já foi transferido: acerto no próximo ciclo.");
	}

	/** IGNORADO com alerta ({@code detalhe} nulo: sem alerta). Copia concorrente que perdeu: ja processado, sem alerta. */
	private Decisao ignorado(long eventoId, UUID pedidoId, String motivo, String detalhe) {
		if (!marcar(eventoId, EventosDePagamento.IGNORADO, motivo, pedidoId)) return Decisao.de(Resultado.JA_PROCESSADO);
		return new Decisao(Resultado.IGNORADO, detalhe == null ? null : motivo, detalhe);
	}

	private Resultado ignorar(long eventoId, String motivo) {
		return marcar(eventoId, EventosDePagamento.IGNORADO, motivo, null) ? Resultado.IGNORADO : Resultado.JA_PROCESSADO;
	}

	private boolean marcar(long eventoId, String status, String motivo, UUID pedidoId) {
		return eventos.marcar(eventoId, status, motivo, pedidoId, relogio.instant());
	}

	/** Depois do commit, sem PII (D11). Falha aqui so vai para o log: o pagamento ja foi decidido. */
	private void alertar(UUID pedidoId, long mpId, String motivo, String detalhe) {
		try {
			var p = pedidos.resumo(pedidoId).orElseThrow();
			var plano = catalogo.planos(List.of(p.planoId())).get(p.planoId());
			alerta.enviar(new AlertaAoAdmin.Alerta(motivo, detalhe, pedidoId, p.status().name(), plano == null ? null : plano.nome(),
					p.valorCentavos()), "pagamento/" + mpId + "/" + motivo);
		} catch (RuntimeException e) {
			EnvioAssincrono.registrar("alerta ao admin (" + motivo + ") do pedido " + pedidoId, e);
		}
	}

	/** Trava presa, banco fora: o MP reenviando resolve. O resto (dado ruim, bug) repetiria a mesma falha. */
	private static boolean transitoria(Throwable e) {
		return e instanceof TransientDataAccessException || e instanceof TransactionException;
	}

	/** {@code external_reference} e o uuid do pedido (a cobranca grava assim); outra coisa e pedido inexistente. */
	static UUID uuid(String referencia) {
		try {
			return referencia != null && referencia.length() == 36 ? UUID.fromString(referencia) : null;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
