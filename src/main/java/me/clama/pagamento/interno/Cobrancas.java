package me.clama.pagamento.interno;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;
import me.clama.infra.formato.Dinheiro;
import me.clama.infra.web.ErroDeNegocio;
import me.clama.pagamento.PagamentoApi;
import me.clama.pedido.PedidoApi;
import me.clama.pedido.PedidoCobravel;
import me.clama.pedido.PedidoNaoEncontradoException;
import me.clama.pedido.StatusPedido;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cobranca Pix do pedido. O pedido fica travado da leitura ao commit, inclusive durante a chamada ao Mercado Pago
 * (INV:90): o segundo clique espera e reaproveita o Pix gravado. Erro do MP desfaz a transacao, entao nada e gravado.
 */
@Service
public class Cobrancas implements PagamentoApi {

	private static final Logger LOG = LoggerFactory.getLogger(Cobrancas.class);
	static final Duration VALIDADE = Duration.ofHours(24);
	static final String PASTORAL_PADRAO = "Tivemos um soluço com o pagamento. Pode tentar novamente em instantes.";
	/** Credencial ou configuracao: nada que o doador resolva. */
	static final String PASTORAL_CONFIGURACAO = "Tivemos um soluço processando seu pagamento. Já estamos olhando — "
			+ "tenta de novo em alguns instantes ou escreve pra contato@clama.me.";
	private static final Set<String> CANCELAVEIS = Set.of("pending", "in_process", "authorized");

	private final PedidoApi pedidos;
	private final CobrancaRepository cobrancas;
	private final MercadoPago mercadoPago;
	private final PagamentoProps props;
	private final Clock relogio;
	private final Repasses repasses;

	Cobrancas(PedidoApi pedidos, CobrancaRepository cobrancas, MercadoPago mercadoPago, PagamentoProps props, Clock relogio,
			Repasses repasses) {
		this.repasses = repasses;
		this.pedidos = pedidos;
		this.cobrancas = cobrancas;
		this.mercadoPago = mercadoPago;
		this.props = props;
		this.relogio = relogio;
	}

	/**
	 * Pix vigente do pedido (a cobranca de maior n, nao cancelada e nao vencida), ou a seguinte ({@code n + 1}). O
	 * vencimento e o que o MP devolve.
	 */
	@Transactional
	public Cobranca pix(UUID pedidoId) {
		var p = travar(pedidoId);
		validar(p);
		var agora = relogio.instant();
		var ultima = cobrancas.findFirstByPedidoIdOrderByNDesc(pedidoId).orElse(null);
		if (ultima != null && ultima.vigente(agora)) {
			LOG.info("Pix reaproveitado (event=pix_reaproveitado, pedido_id={}, mp_pagamento_id={})", pedidoId, ultima.getMpPagamentoId());
			return ultima;
		}
		var n = ultima == null ? 1 : ultima.getN() + 1;
		MercadoPago.Pix pix;
		try {
			pix = mercadoPago.criarPix(p, Cobranca.chave(pedidoId, n), agora.plus(VALIDADE).truncatedTo(ChronoUnit.SECONDS));
		} catch (MercadoPago.Falha f) {
			throw paraResposta(f, pedidoId);
		}
		LOG.info("Pix criado (event=pix_criado, pedido_id={}, n={}, mp_pagamento_id={})", pedidoId, n, pix.id());
		return cobrancas.save(Cobranca.nova(pedidoId, n, pix, agora));
	}

	/** Para o webhook (T5): o pagamento e de alguma cobranca deste pedido (vigente, vencida ou cancelada)? */
	@Transactional(readOnly = true)
	public boolean ehCobrancaDoPedido(UUID pedidoId, long mpPagamentoId) {
		return cobrancas.existsByPedidoIdAndMpPagamentoId(pedidoId, mpPagamentoId);
	}

	/** Para o webhook: este pagamento e o que pagou o pedido? (Reentrega e estorno; outro pagamento aprovado e orfao.) */
	@Transactional(readOnly = true)
	public boolean pagouOPedido(UUID pedidoId, long mpPagamentoId) {
		return cobrancas.existsByPedidoIdAndMpPagamentoIdAndPagaEmIsNotNull(pedidoId, mpPagamentoId);
	}

	/** Para o webhook, na transacao com o pedido travado: a cobranca deste pagamento pagou o pedido. */
	@Transactional
	public void registrarPagamento(long mpPagamentoId, Instant agora) {
		cobrancas.findByMpPagamentoId(mpPagamentoId).orElseThrow().pagar(agora);
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, Pagamento> pagamentos(Collection<UUID> pedidoIds) {
		if (pedidoIds.isEmpty()) return Map.of();
		return cobrancas.findByPedidoIdInAndPagaEmIsNotNull(pedidoIds).stream()
				.collect(Collectors.toMap(Cobranca::getPedidoId, c -> new Pagamento(c.getPagaEm(), c.getMpPagamentoId())));
	}

	@Override
	@Transactional(readOnly = true)
	public Set<UUID> pedidosComPagamento(Collection<UUID> pedidoIds) {
		if (pedidoIds.isEmpty()) return Set.of();
		var ids = new HashSet<>(pagamentos(pedidoIds).keySet());
		ids.addAll(repasses.pedidosComRepasse(pedidoIds));
		return ids;
	}

	/** Marca a cobranca mais recente como cancelada (a linha fica: historico e n nunca recomecam). */
	@Override
	@Transactional
	public void cancelarCobranca(UUID pedidoId) {
		travar(pedidoId);
		var c = cobrancas.findFirstByPedidoIdOrderByNDesc(pedidoId).orElse(null);
		if (c == null || c.getCanceladaEm() != null) return;
		try {
			var status = mercadoPago.pagamento(c.getMpPagamentoId()).status();
			if ("approved".equals(status)) throw jaAprovado();
			if (CANCELAVEIS.contains(status)) {
				try {
					mercadoPago.cancelar(c.getMpPagamentoId());
				} catch (MercadoPago.Falha f) {
					// o MP recusa o cancelamento de quem foi aprovado entre a leitura e o PUT: 409, nao 422
					if (f.quatroXX() && "approved".equals(mercadoPago.pagamento(c.getMpPagamentoId()).status())) throw jaAprovado();
					throw f;
				}
			}
		} catch (MercadoPago.Falha f) {
			throw paraResposta(f, pedidoId);
		}
		c.cancelar(relogio.instant());
		LOG.info("Pix cancelado (event=pix_cancelado, pedido_id={}, mp_pagamento_id={})", pedidoId, c.getMpPagamentoId());
	}

	private static ErroDeNegocio jaAprovado() {
		return new ErroDeNegocio(HttpStatus.CONFLICT, "pagamento_ja_aprovado", "Pagamento já aprovado no Mercado Pago.",
				"O pagamento já caiu; reprocesse o evento.");
	}

	/** Espera no maximo 15 s pela trava (lock_timeout do {@code PedidoRepository.travar}, provado no PixTest). */
	private PedidoCobravel travar(UUID pedidoId) {
		try {
			return pedidos.travarParaCobranca(pedidoId);
		} catch (PedidoNaoEncontradoException e) {
			throw naoEncontrado();
		} catch (PessimisticLockingFailureException e) {
			// outra requisicao segurou o pedido alem do lock_timeout (ex.: MP lento): pede para tentar de novo
			LOG.warn("Pix: trava do pedido estourou (event=checkout_lock_timeout, pedido_id={})", pedidoId);
			throw new ErroDeNegocio(HttpStatus.SERVICE_UNAVAILABLE, "pagamento_indisponivel", "Pedido em processamento.",
					PASTORAL_PADRAO);
		}
	}

	private void validar(PedidoCobravel p) {
		if (p.status() != StatusPedido.AGUARDANDO_PAGAMENTO) {
			throw new ErroDeNegocio(HttpStatus.CONFLICT, "pedido_ja_pago", "Pedido já foi pago ou processado.",
					"Esse pedido já foi pago, vamos te encaminhar para a confirmação.");
		}
		if (p.cpfCnpj() == null || p.cpfCnpj().isBlank()) {
			throw new ErroDeNegocio(HttpStatus.UNPROCESSABLE_ENTITY, "cpf_obrigatorio",
					"CPF/CNPJ é obrigatório para gerar a cobrança.",
					"Precisamos do seu CPF para gerar a cobrança. Volte ao formulário e complete o cadastro.");
		}
		if (p.valorCentavos() < props.valorMinimoCentavos()) { // a criacao ja confere; aqui e defesa (D7)
			var minimo = Dinheiro.reais(props.valorMinimoCentavos());
			throw new ErroDeNegocio(HttpStatus.UNPROCESSABLE_ENTITY, "valor_abaixo_do_minimo",
					"Valor menor que o mínimo aceito (" + minimo + ").", "O valor mínimo aceito pelo processador de "
							+ "pagamentos é " + minimo + ". Escolha um valor igual ou maior pra continuar.");
		}
	}

	/** 4xx de dado recusado: 422. Credencial ou token ausente: 503 + Sentry. Rede, 5xx, sem QR: 503. */
	static ErroDeNegocio paraResposta(MercadoPago.Falha f, UUID pedidoId) {
		LOG.warn("Pix falhou (event=checkout_failed, pedido_id={}, status={}, configuracao={}, descricao={})", pedidoId,
				f.status(), f.configuracao(), f.descricao());
		if (f.configuracao()) {
			Sentry.captureMessage("Mercado Pago: credencial recusada ou token ausente no Pix (ver logs)", SentryLevel.ERROR);
			return new ErroDeNegocio(HttpStatus.SERVICE_UNAVAILABLE, "pagamento_indisponivel", f.getMessage(), PASTORAL_CONFIGURACAO);
		}
		if (f.quatroXX()) {
			return new ErroDeNegocio(HttpStatus.UNPROCESSABLE_ENTITY, "pagamento_recusado", f.getMessage(), PASTORAL_PADRAO);
		}
		return new ErroDeNegocio(HttpStatus.SERVICE_UNAVAILABLE, "pagamento_indisponivel", f.getMessage(), PASTORAL_PADRAO);
	}

	public static ErroDeNegocio naoEncontrado() {
		return new ErroDeNegocio(HttpStatus.NOT_FOUND, "pedido_nao_encontrado", "Pedido não encontrado.",
				"Não encontramos esse pedido. Verifique se o link está correto.");
	}

}
