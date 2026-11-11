package me.clama.pedido.interno;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import me.clama.conta.ContaApi;
import me.clama.pedido.DadosParaOracao;
import me.clama.pedido.GeracaoSolicitada;
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

	private final PedidoRepository pedidos;
	private final ContaApi conta;
	private final ApplicationEventPublisher eventos;
	private final Clock relogio;

	PedidoApiImpl(PedidoRepository pedidos, ContaApi conta, ApplicationEventPublisher eventos, Clock relogio) {
		this.pedidos = pedidos;
		this.conta = conta;
		this.eventos = eventos;
		this.relogio = relogio;
	}

	private Pedido travado(UUID id) {
		return pedidos.travar(id).orElseThrow(PedidoNaoEncontradoException::new);
	}

	/** Trava, aplica a transicao e grava (o JPA so atualiza o que mudou). */
	private Pedido mudar(UUID id, Consumer<Pedido> transicao) {
		var p = travado(id);
		transicao.accept(p);
		return p;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<PedidoResumo> resumo(UUID id) {
		return pedidos.findById(id).map(p -> new PedidoResumo(p.getId(), p.getStatus(), p.getPlanoId(), p.getInstituicaoId(),
				p.getValorCentavos(), p.isGratuito(), p.getCriadoEm(), p.getAtualizadoEm()));
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
		p.confirmarPagamento();
		if (p.getUsuarioId() == null) p.vincularUsuario(conta.provisionarOuVincular(p.getEmail(), p.getNome()).id());
		eventos.publishEvent(new GeracaoSolicitada(id));
		return true;
	}

	@Override
	public void concederGratuidade(UUID id) {
		mudar(id, Pedido::concederGratuidade);
		eventos.publishEvent(new GeracaoSolicitada(id));
	}

	@Override
	public void solicitarRegeneracao(UUID id) {
		mudar(id, Pedido::solicitarRegeneracao);
		eventos.publishEvent(new GeracaoSolicitada(id));
	}

	@Override
	public void solicitarReenvio(UUID id) {
		mudar(id, Pedido::solicitarReenvio);
		eventos.publishEvent(new ReenvioSolicitado(id));
	}

	@Override
	@Transactional(readOnly = true)
	public DadosParaOracao dadosParaOracao(UUID id) {
		var p = pedidos.findById(id).orElseThrow(PedidoNaoEncontradoException::new);
		return new DadosParaOracao(p.getId(), p.getStatus(), p.getPlanoId(), p.isGratuito(), p.getNome(), p.getEmail(),
				p.getIdade(), p.getSexo(), p.getPedidoOracao(), p.getOracao(), p.getTentativas());
	}

	@Override
	public void iniciarGeracao(UUID id) {
		mudar(id, Pedido::iniciarGeracao);
	}

	@Override
	public void registrarOracao(UUID id, String texto) {
		mudar(id, p -> p.registrarOracao(texto));
	}

	@Override
	public void agendarNovaTentativa(UUID id, String erro) {
		mudar(id, p -> p.agendarNovaTentativa(erro));
	}

	@Override
	public void falhar(UUID id, String motivo) {
		mudar(id, p -> p.falhar(motivo));
	}

	@Override
	public void marcarEnviado(UUID id) {
		mudar(id, Pedido::marcarEnviado);
	}

	@Override
	@Transactional(readOnly = true)
	public List<UUID> emAndamentoParados(Duration janela) {
		return pedidos.parados(relogio.instant().minus(janela)).stream().map(Pedido::getId).toList();
	}
}
