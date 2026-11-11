package me.clama.pedido.interno;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import me.clama.conta.ContaApi;
import me.clama.infra.cripto.HmacDeIdentificador;
import me.clama.pedido.CorrecaoDeDados;
import me.clama.pedido.DadosParaOracao;
import me.clama.pedido.GeracaoSolicitada;
import me.clama.pedido.Identificadores;
import me.clama.pedido.NovoPedido;
import me.clama.pedido.PedidoApi;
import me.clama.pedido.PedidoCobravel;
import me.clama.pedido.PedidoNaoEncontradoException;
import me.clama.pedido.PedidoResumo;
import me.clama.pedido.ReenvioSolicitado;
import me.clama.pedido.StatusPedido;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class PedidoApiImpl implements PedidoApi {

	private static final Set<StatusPedido> EM_ANDAMENTO = EnumSet.of(StatusPedido.PAGO, StatusPedido.GERANDO_ORACAO,
			StatusPedido.ORACAO_GERADA, StatusPedido.AGUARDANDO_NOVA_TENTATIVA);

	private final PedidoRepository pedidos;
	private final CriacaoDePedido criacao;
	private final ContaApi conta;
	private final ApplicationEventPublisher eventos;
	private final Clock relogio;
	private final HmacDeIdentificador hmac;
	@PersistenceContext
	private EntityManager em;

	PedidoApiImpl(PedidoRepository pedidos, CriacaoDePedido criacao, ContaApi conta, ApplicationEventPublisher eventos, Clock relogio,
			HmacDeIdentificador hmac) {
		this.hmac = hmac;
		this.pedidos = pedidos;
		this.criacao = criacao;
		this.conta = conta;
		this.eventos = eventos;
		this.relogio = relogio;
	}

	/**
	 * Trava e relê. O {@code Pedido} nao tem {@code @Version}: se a transacao ja tinha lido o pedido (um {@code resumo}
	 * antes), o {@code FOR UPDATE} devolveria a instancia velha do contexto e a transicao rodaria sobre um status vencido.
	 */
	private Pedido travado(UUID id) {
		var p = pedidos.travar(id).orElseThrow(PedidoNaoEncontradoException::new);
		em.refresh(p);
		return p;
	}

	/** Trava, aplica a transicao e grava (o JPA so atualiza o que mudou). */
	private Pedido mudar(UUID id, Consumer<Pedido> transicao) {
		var p = travado(id);
		transicao.accept(p);
		return p;
	}

	@Override
	public UUID criarFreemium(NovoPedido corpo, String ip) {
		return criacao.criarFreemium(corpo, ip).getId();
	}

	@Override
	@Transactional(readOnly = true)
	public boolean existePendenteDeConfirmacao(String emailHmac, String cpfHmac, Instant criadoDesde) {
		return pedidos.existePendente(emailHmac, cpfHmac, criadoDesde);
	}

	@Override
	@Transactional(readOnly = true)
	public boolean cpfTemConta(String cpfHmac) {
		return cpfHmac != null && pedidos.cpfTemDono(cpfHmac);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public Identificadores confirmarEmail(UUID id) {
		var p = travado(id);
		p.confirmarEmail(relogio.instant());
		p.vincularUsuario(conta.provisionarOuVincular(p.getEmail(), p.getNome()).id());
		eventos.publishEvent(new GeracaoSolicitada(id));
		return new Identificadores(p.getEmailHmac(), p.getCpfHmac(), hmac.ip(p.getConsentimentoIp()));
	}

	@Override
	public int expirarConfirmacoes(Instant criadosAntesDe) {
		return pedidos.expirarPendentesAntes(criadosAntesDe, relogio.instant());
	}

	@Override
	@Transactional(readOnly = true)
	public int contarConfirmacoesVencidas(Instant criadosAntesDe) {
		return pedidos.contarPendentesAntes(criadosAntesDe);
	}

	/** Ids por consulta: o driver aceita 32767 parametros, e a auditoria do ledger passa todo pedido pago sem repasse. */
	static final int LOTE = 5_000;

	private static <T> java.util.List<T> emLotes(java.util.Collection<UUID> ids, java.util.function.Function<java.util.List<UUID>, java.util.List<T>> consulta) {
		var todos = new java.util.ArrayList<UUID>(ids);
		var saida = new java.util.ArrayList<T>();
		for (int i = 0; i < todos.size(); i += LOTE) saida.addAll(consulta.apply(todos.subList(i, Math.min(i + LOTE, todos.size()))));
		return saida;
	}

	@Override
	@Transactional(readOnly = true)
	public java.util.Map<UUID, PedidoResumo> resumos(java.util.Collection<UUID> ids) {
		return emLotes(ids, pedidos::resumos).stream().collect(java.util.stream.Collectors.toMap(PedidoResumo::id, r -> r));
	}

	@Override
	@Transactional(readOnly = true)
	public java.util.Map<UUID, me.clama.pedido.Doador> doadores(java.util.Collection<UUID> ids) {
		return emLotes(ids, pedidos::doadores).stream().collect(java.util.stream.Collectors.toMap(PedidoRepository.NomeEEmail::id,
				d -> new me.clama.pedido.Doador(d.nome(), d.email())));
	}

	@Override
	@Transactional(readOnly = true)
	public java.util.Map<UUID, String> nomes(java.util.Collection<UUID> ids) {
		var mapa = new java.util.HashMap<UUID, String>();
		emLotes(ids, pedidos::nomes).forEach(n -> mapa.put(n.id(), n.nome()));
		return mapa;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<PedidoResumo> resumo(UUID id) {
		return pedidos.findById(id).map(p -> new PedidoResumo(p.getId(), p.getStatus(), p.getPlanoId(), p.getInstituicaoId(),
				p.getValorCentavos(), p.isGratuito(), p.getCriadoEm(), p.getAtualizadoEm(), p.getUltimoErro()));
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public PedidoCobravel travarParaCobranca(UUID id) {
		var p = travado(id);
		return new PedidoCobravel(p.getId(), p.getStatus(), p.getValorCentavos(), p.getNome(), p.getEmail(), p.getCpfCnpj());
	}

	@Override
	public boolean confirmarPagamento(UUID id) {
		var p = travado(id);
		if (p.getStatus() != StatusPedido.AGUARDANDO_PAGAMENTO) return false;
		p.confirmarPagamento(relogio.instant());
		if (p.getUsuarioId() == null) p.vincularUsuario(conta.provisionarOuVincular(p.getEmail(), p.getNome()).id());
		eventos.publishEvent(new GeracaoSolicitada(id));
		return true;
	}

	@Override
	public void concederGratuidade(UUID id) {
		mudar(id, p -> p.concederGratuidade(relogio.instant()));
		eventos.publishEvent(new GeracaoSolicitada(id));
	}

	@Override
	public void solicitarRegeneracao(UUID id) {
		mudar(id, p -> p.solicitarRegeneracao(relogio.instant()));
		eventos.publishEvent(new GeracaoSolicitada(id));
	}

	@Override
	public void solicitarReenvio(UUID id) {
		mudar(id, p -> p.solicitarReenvio(relogio.instant()));
		eventos.publishEvent(new ReenvioSolicitado(id));
	}

	/** Admin (D22), so do modulo ({@link AdminDePedidos}): corrige so dados, com o pedido travado; devolve os campos que mudaram. */
	List<String> corrigirDados(UUID id, CorrecaoDeDados correcao) {
		return travado(id).corrigirDados(correcao, hmac);
	}

	@Override
	@Transactional(readOnly = true)
	public DadosParaOracao dadosParaOracao(UUID id) {
		var p = pedidos.findById(id).orElseThrow(PedidoNaoEncontradoException::new);
		return new DadosParaOracao(p.getId(), p.getStatus(), p.getPlanoId(), p.isGratuito(), p.getNome(), p.getEmail(),
				p.getIdade(), p.getSexo(), p.getPedidoOracao(), p.getOracao(), p.getTentativas(), p.getUltimoErro());
	}

	@Override
	public void iniciarGeracao(UUID id) {
		mudar(id, p -> p.iniciarGeracao(relogio.instant()));
	}

	@Override
	public void registrarOracao(UUID id, String texto) {
		mudar(id, p -> p.registrarOracao(texto, relogio.instant()));
	}

	@Override
	public void agendarNovaTentativa(UUID id, String erro) {
		mudar(id, p -> p.agendarNovaTentativa(erro, relogio.instant()));
	}

	@Override
	public void falhar(UUID id, String motivo) {
		mudar(id, p -> p.falhar(motivo, relogio.instant()));
	}

	@Override
	public void marcarEnviado(UUID id) {
		mudar(id, p -> p.marcarEnviado(relogio.instant()));
	}

	@Override
	@Transactional(readOnly = true)
	public List<UUID> emAndamentoParados(Duration janela) {
		return pedidos.parados(relogio.instant().minus(janela));
	}

	@Override
	public boolean falharSeParado(UUID id, Instant antesDe, String motivo) {
		var p = travado(id);
		if (!EM_ANDAMENTO.contains(p.getStatus()) || !p.getAtualizadoEm().isBefore(antesDe)) return false;
		p.falhar(motivo, relogio.instant());
		return true;
	}
}
