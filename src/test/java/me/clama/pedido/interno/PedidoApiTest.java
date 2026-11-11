package me.clama.pedido.interno;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import me.clama.BancoDeTeste;
import me.clama.conta.interno.UsuarioRepository;
import me.clama.pedido.GeracaoSolicitada;
import me.clama.pedido.PedidoApi;
import me.clama.pedido.PedidoNaoEncontradoException;
import me.clama.pedido.ReenvioSolicitado;
import me.clama.pedido.StatusPedido;
import me.clama.pedido.TransicaoInvalidaException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(BancoDeTeste.class)
@RecordApplicationEvents
class PedidoApiTest {

	@Autowired PedidoApi api;
	@Autowired PedidoRepository pedidos;
	@Autowired UsuarioRepository usuarios;
	@Autowired JdbcTemplate jdbc;
	@Autowired TransactionTemplate tx;
	@Autowired ApplicationEvents eventos;

	@BeforeEach @AfterEach
	void limpar() {
		jdbc.update("delete from pedido");
		jdbc.update("delete from usuario where email like 'doador-%'");
	}

	private Pedido novo(String email) {
		return pedidos.saveAndFlush(Pedido.novo(new Pedido.Dados(null, planoId(), null, 2000, "Maria Souza", email, "52998224725", null, null, null, "Pela familia", Instant.now(), "203.0.113.9", "2026-01"), PedidoTest.HMAC, Instant.now()));
	}

	private Long planoId() {
		return jdbc.queryForObject("select id from plano where tipo = 'VALOR_LIVRE'", Long.class);
	}

	@Test
	void confirmarPagamentoVinculaContaEPublicaGeracaoSolicitada() {
		var email = "doador-" + UUID.randomUUID() + "@exemplo.com";
		var id = novo(email).getId();
		assertThat(api.confirmarPagamento(id)).isTrue();
		var p = pedidos.findById(id).orElseThrow();
		assertThat(p.getStatus()).isEqualTo(StatusPedido.PAGO);
		assertThat(p.getUsuarioId()).isNotNull();
		assertThat(usuarios.findByEmail(email)).isPresent();
		assertThat(eventos.stream(GeracaoSolicitada.class)).extracting(GeracaoSolicitada::pedidoId).containsExactly(id);
	}

	/**
	 * Regressao: ler o pedido e depois travar na mesma transacao. Sem {@code @Version}, o {@code FOR UPDATE} devolveria a
	 * instancia velha do contexto e confirmaria de novo um pedido que outra transacao ja pagou.
	 */
	@Test
	void lerAntesDeTravarNaMesmaTransacaoEnxergaOEstadoAtual() {
		var id = novo("doador-" + UUID.randomUUID() + "@exemplo.com").getId();
		var confirmou = tx.execute(s -> {
			assertThat(api.resumo(id).orElseThrow().status()).isEqualTo(StatusPedido.AGUARDANDO_PAGAMENTO); // fica no contexto
			var outra = new Thread(() -> jdbc.update("update pedido set status = 'PAGO' where id = ?", id)); // commit proprio
			outra.start();
			try {
				outra.join();
			} catch (InterruptedException e) {
				throw new IllegalStateException(e);
			}
			return api.confirmarPagamento(id);
		});
		assertThat(confirmou).isFalse();
		assertThat(eventos.stream(GeracaoSolicitada.class)).isEmpty();
	}

	@Test
	void confirmarPagamentoRepetidoNaoFazNadaEDevolveFalse() {
		var id = novo("doador-" + UUID.randomUUID() + "@exemplo.com").getId();
		api.confirmarPagamento(id);
		eventos.clear();
		assertThat(api.confirmarPagamento(id)).isFalse();
		assertThat(eventos.stream(GeracaoSolicitada.class)).isEmpty();
	}

	@Test
	void pedidoDeContaLogadaMantemOVinculo() {
		var u = tx.execute(s -> usuarios.saveAndFlush(me.clama.conta.interno.Usuario.novo("doador-x-" + UUID.randomUUID() + "@exemplo.com", "Fulano", me.clama.conta.interno.Papel.CLIENTE, Instant.now())));
		var p = pedidos.saveAndFlush(Pedido.novo(new Pedido.Dados(u.getId(), planoId(), null, 2000, "Fulano", "outro-" + UUID.randomUUID() + "@exemplo.com", null, null, null, null, "t", Instant.now(), null, "2026-01"), PedidoTest.HMAC, Instant.now()));
		api.confirmarPagamento(p.getId());
		assertThat(pedidos.findById(p.getId()).orElseThrow().getUsuarioId()).isEqualTo(u.getId());
		jdbc.update("delete from pedido");
		jdbc.update("delete from usuario where id = ?", u.getId());
	}

	@Test
	void idInexistenteVira404() {
		assertThatThrownBy(() -> api.confirmarPagamento(UUID.randomUUID())).isInstanceOf(PedidoNaoEncontradoException.class);
		assertThat(api.resumo(UUID.randomUUID())).isEmpty();
	}

	@Test
	void resumoEDadosParaOracao() {
		var id = novo("doador-" + UUID.randomUUID() + "@exemplo.com").getId();
		var r = api.resumo(id).orElseThrow();
		assertThat(r.status()).isEqualTo(StatusPedido.AGUARDANDO_PAGAMENTO);
		assertThat(r.valorCentavos()).isEqualTo(2000);
		var d = api.dadosParaOracao(id);
		assertThat(d.nome()).isEqualTo("Maria Souza");
		assertThat(d.pedidoOracao()).isEqualTo("Pela familia");
		assertThat(d.oracao()).isNull();
	}

	@Test
	void cicloDeGeracaoPelaApi() {
		var id = novo("doador-" + UUID.randomUUID() + "@exemplo.com").getId();
		api.confirmarPagamento(id);
		api.iniciarGeracao(id);
		api.agendarNovaTentativa(id, "timeout");
		assertThat(api.resumo(id).orElseThrow().status()).isEqualTo(StatusPedido.AGUARDANDO_NOVA_TENTATIVA);
		api.iniciarGeracao(id);
		api.registrarOracao(id, "Senhor...");
		assertThat(api.dadosParaOracao(id).oracao()).isEqualTo("Senhor...");
		api.marcarEnviado(id);
		assertThat(api.resumo(id).orElseThrow().status()).isEqualTo(StatusPedido.ENVIADA);
	}

	@Test
	void falharEComandosDoAdminPublicamEventos() {
		var id = novo("doador-" + UUID.randomUUID() + "@exemplo.com").getId();
		api.confirmarPagamento(id);
		api.falhar(id, "credit_balance");
		assertThat(pedidos.findById(id).orElseThrow().getUltimoErro()).isEqualTo("credit_balance");
		eventos.clear();
		api.solicitarRegeneracao(id);
		assertThat(eventos.stream(GeracaoSolicitada.class)).hasSize(1);
		api.iniciarGeracao(id);
		api.registrarOracao(id, "x");
		api.marcarEnviado(id);
		api.solicitarReenvio(id);
		assertThat(eventos.stream(ReenvioSolicitado.class)).extracting(ReenvioSolicitado::pedidoId).containsExactly(id);
	}

	@Test
	void concederGratuidadePublicaGeracao() {
		var id = novo("doador-" + UUID.randomUUID() + "@exemplo.com").getId();
		api.concederGratuidade(id);
		assertThat(eventos.stream(GeracaoSolicitada.class)).hasSize(1);
		assertThat(pedidos.findById(id).orElseThrow().isGratuito()).isTrue();
	}

	@Test
	void transicaoInvalidaPelaApiNaoPublicaEvento() {
		var id = novo("doador-" + UUID.randomUUID() + "@exemplo.com").getId();
		assertThatThrownBy(() -> api.iniciarGeracao(id)).isInstanceOf(TransicaoInvalidaException.class);
		assertThat(eventos.stream(GeracaoSolicitada.class)).isEmpty();
	}

	@Test
	void travarParaCobrancaExigeTransacao() {
		var id = novo("doador-" + UUID.randomUUID() + "@exemplo.com").getId();
		assertThatThrownBy(() -> api.travarParaCobranca(id)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
		var c = tx.execute(s -> api.travarParaCobranca(id));
		assertThat(c.valorCentavos()).isEqualTo(2000);
		assertThat(c.cpfCnpj()).isEqualTo("52998224725");
		assertThat(c.email()).startsWith("doador-");
	}

	@Test
	void emAndamentoParadosPelaJanela() {
		var id = novo("doador-" + UUID.randomUUID() + "@exemplo.com").getId();
		api.confirmarPagamento(id);
		assertThat(api.emAndamentoParados(Duration.ofMinutes(20))).isEmpty();
		jdbc.update("update pedido set atualizado_em = now() - interval '30 minutes'");
		assertThat(api.emAndamentoParados(Duration.ofMinutes(20))).containsExactly(id);
	}

	@Test
	void falharSeParadoSoFalhaOQueNaoAndou() {
		var id = novo("doador-" + UUID.randomUUID() + "@exemplo.com").getId();
		api.confirmarPagamento(id);
		jdbc.update("update pedido set atualizado_em = now() - interval '30 minutes'");
		var corte = Instant.now().minus(Duration.ofMinutes(20));
		assertThat(api.emAndamentoParados(Duration.ofMinutes(20))).containsExactly(id);
		// entre a listagem e a falha, o pedido andou
		api.iniciarGeracao(id);
		assertThat(api.falharSeParado(id, corte, "pedido_preso")).isFalse();
		assertThat(api.resumo(id).orElseThrow().status()).isEqualTo(StatusPedido.GERANDO_ORACAO);
		// parado de verdade: falha com o codigo
		jdbc.update("update pedido set atualizado_em = now() - interval '30 minutes'");
		assertThat(api.falharSeParado(id, corte, "pedido_preso")).isTrue();
		assertThat(pedidos.findById(id).orElseThrow().getUltimoErro()).isEqualTo("pedido_preso");
		// ja em ERRO: nao e mais elegivel
		jdbc.update("update pedido set atualizado_em = now() - interval '30 minutes'");
		assertThat(api.falharSeParado(id, corte, "pedido_preso")).isFalse();
	}
}
