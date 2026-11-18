package me.clama.pedido.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import me.clama.pedido.HistoricoDePagamento.CobrancaDoPedido;
import me.clama.pedido.HistoricoDePagamento.EventoDoPedido;
import me.clama.pedido.HistoricoDePagamento.RepasseDoPedido;
import me.clama.pedido.StatusPedido;
import org.springframework.lang.Nullable;

/**
 * Detalhe do pedido no admin: PII decifrada, conjunto fixo de chaves. Nao traz CPF/CNPJ, consentimento nem nada do Pix
 * (copia-e-cola, QR): as cobrancas so mostram o estado de cada uma. {@code risco}: a triagem de crise, nulo enquanto nao
 * triado (ou depois da purga do texto).
 */
record PedidoAdminDetalhe(UUID id, Instant criadoEm, Instant atualizadoEm, StatusPedido status, boolean gratuito,
		long valorCentavos, @Nullable Long usuarioId, String nome, String email, @Nullable String telefone,
		@Nullable Short idade, @Nullable String sexo, @Nullable String pedidoOracao, @Nullable String oracaoGerada,
		@Nullable Plano plano, @Nullable Instituicao instituicao, int tentativas, @Nullable String ultimoErro,
		@Nullable Boolean risco, @Nullable String tema,
		List<CobrancaDoPedido> cobrancas, @Nullable RepasseDoPedido repasse, List<EventoDoPedido> eventos) {

	record Plano(Long id, String nome, long valorCentavos) {}

	record Instituicao(Long id, String nome) {}
}
