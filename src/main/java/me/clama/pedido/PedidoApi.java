package me.clama.pedido;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Interface do modulo para {@code pagamento} e {@code oracao}. Todo comando trava a linha do pedido, aplica uma
 * transicao da maquina de estados e, se for o caso, publica o evento na mesma transacao. Transicao nao permitida:
 * {@link TransicaoInvalidaException} (409); id inexistente: {@link PedidoNaoEncontradoException} (404).
 */
public interface PedidoApi {

	Optional<PedidoResumo> resumo(UUID id);

	/** Trava a linha ate o fim da transacao do chamador (que precisa existir); lock preso por mais de 15 s estoura. */
	PedidoCobravel travarParaCobranca(UUID id);

	/**
	 * AGUARDANDO_PAGAMENTO para PAGO, vincula (ou cria) a conta do doador se o pedido nao tem dono e publica
	 * {@link GeracaoSolicitada}. {@code false} se o pedido ja nao esta aguardando pagamento (webhook repetido).
	 */
	boolean confirmarPagamento(UUID id);

	/** Admin: AGUARDANDO_PAGAMENTO para PAGO sem cobrar (valor 0) e publica {@link GeracaoSolicitada}. */
	void concederGratuidade(UUID id);

	/** Admin: ERRO sem oracao volta a PAGO e publica {@link GeracaoSolicitada}. */
	void solicitarRegeneracao(UUID id);

	/** Admin: pede reenvio de oracao ja gerada e publica {@link ReenvioSolicitado}. */
	void solicitarReenvio(UUID id);

	/** Admin (D22): corrige so dados, com o pedido travado; devolve os nomes dos campos que mudaram. Status, plano e valor ficam. */
	List<String> corrigirDados(UUID id, CorrecaoDeDados correcao);

	DadosParaOracao dadosParaOracao(UUID id);

	/** Job de geracao, ao comecar (tambem na retentativa). Nunca a partir de AGUARDANDO_PAGAMENTO. */
	void iniciarGeracao(UUID id);

	void registrarOracao(UUID id, String texto);

	/** Esgotou as retentativas do job de geracao: conta a tentativa e espera. */
	void agendarNovaTentativa(UUID id, String erro);

	/** {@code motivo}: codigo curto ({@code credit_balance}, {@code pedido_preso}, {@code falha_envio}), nunca mensagem de excecao. */
	void falhar(UUID id, String motivo);

	/** Envio concluido; repetir em ENVIADA (reenvio) nao muda o status. */
	void marcarEnviado(UUID id);

	/** Ids (sem ler PII) de pedidos PAGO, GERANDO_ORACAO, ORACAO_GERADA ou AGUARDANDO_NOVA_TENTATIVA sem mudar ha mais que a janela. */
	List<UUID> emAndamentoParados(Duration janela);

	/**
	 * Falha o pedido so se, com a linha travada, ele ainda esta em andamento e sem mudar desde {@code antesDe}
	 * (a varredura lista sem trava; nesse intervalo o pedido pode ter andado). {@code motivo} e um codigo curto.
	 */
	boolean falharSeParado(UUID id, java.time.Instant antesDe, String motivo);
}
