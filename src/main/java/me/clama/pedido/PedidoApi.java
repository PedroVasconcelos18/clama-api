package me.clama.pedido;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Interface do modulo para {@code pagamento} e {@code oracao}. Todo comando trava a linha do pedido, aplica uma
 * transicao da maquina de estados e, se for o caso, publica o evento na mesma transacao. Transicao nao permitida:
 * {@link TransicaoInvalidaException} (409); id inexistente: {@link PedidoNaoEncontradoException} (404).
 */
public interface PedidoApi {

	/**
	 * Freemium: plano gratuito (503 {@code freemium_indisponivel} se faltar), valor 0, AGUARDANDO_CONFIRMACAO_EMAIL,
	 * consentimento com o {@code ip}. Sem conta e sem evento: a oracao so e pedida em {@link #confirmarEmail}.
	 */
	UUID criarFreemium(NovoPedido corpo, String ip);

	/** Ha pedido freemium aguardando confirmacao, criado desde {@code criadoDesde}, com esse e-mail ou CPF (HMAC; {@code null} nao casa). */
	boolean existePendenteDeConfirmacao(String emailHmac, String cpfHmac, Instant criadoDesde);

	/** Algum pedido com esse CPF ja tem dono (conta vinculada). */
	boolean cpfTemConta(String cpfHmac);

	/**
	 * AGUARDANDO_CONFIRMACAO_EMAIL para PAGO, cria ou vincula a conta (so aqui nasce a conta do freemium) e publica
	 * {@link GeracaoSolicitada}. Fora do estado: {@link TransicaoInvalidaException}. Exige transacao do chamador.
	 */
	Identificadores confirmarEmail(UUID id);

	/** Reconciliacao: pendentes criados antes de {@code criadosAntesDe} vao a CONFIRMACAO_EXPIRADA; devolve quantos. */
	int expirarConfirmacoes(Instant criadosAntesDe);

	/** Quantos {@link #expirarConfirmacoes} expiraria (para o dry-run). */
	int contarConfirmacoesVencidas(Instant criadosAntesDe);

	Optional<PedidoResumo> resumo(UUID id);

	/** Em lote e sem PII (nem decifra nada): ids inexistentes ficam de fora. */
	Map<UUID, PedidoResumo> resumos(Collection<UUID> ids);

	/** Em lote: decifra so nome e e-mail (relatorio de repasse); ids inexistentes ficam de fora. Nunca logar o resultado. */
	Map<UUID, Doador> doadores(Collection<UUID> ids);

	/** Em lote: so o nome (o e-mail nem e decifrado); ids inexistentes ficam de fora. Nunca logar o resultado. */
	Map<UUID, String> nomes(Collection<UUID> ids);

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
	boolean falharSeParado(UUID id, Instant antesDe, String motivo);
}
