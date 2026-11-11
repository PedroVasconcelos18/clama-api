package me.clama.pedido.interno;

import static me.clama.pedido.StatusPedido.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.function.Consumer;
import me.clama.pedido.StatusPedido;
import me.clama.pedido.TransicaoInvalidaException;
import org.junit.jupiter.api.Test;

class PedidoTest {

	static Pedido novo() {
		return novo(1L, 2L);
	}

	static Pedido novo(Long planoId, Long instituicaoId) {
		return Pedido.novo(new Pedido.Dados(null, planoId, instituicaoId, 2000, "Maria", "maria@exemplo.com", "hm", "52998224725", "hc",
				"11999990000", (short) 30, "F", "Pela minha familia", java.time.Instant.parse("2026-11-11T12:00:00Z"),
				"203.0.113.9"));
	}

	/** Leva o pedido ao status por caminhos legitimos, para testar a transicao a partir dele. */
	static Pedido em(StatusPedido alvo, boolean comOracao) {
		var p = novo();
		switch (alvo) {
			case AGUARDANDO_PAGAMENTO -> {}
			case PAGO -> p.confirmarPagamento();
			case GERANDO_ORACAO -> { p.confirmarPagamento(); p.iniciarGeracao(); }
			case ORACAO_GERADA -> { p.confirmarPagamento(); p.iniciarGeracao(); p.registrarOracao("Senhor..."); }
			case ENVIADA -> { p.confirmarPagamento(); p.iniciarGeracao(); p.registrarOracao("Senhor..."); p.marcarEnviado(); }
			case AGUARDANDO_NOVA_TENTATIVA -> { p.confirmarPagamento(); p.iniciarGeracao(); p.agendarNovaTentativa("x"); }
			case ERRO -> { p.confirmarPagamento(); p.iniciarGeracao(); if (comOracao) p.registrarOracao("Senhor..."); p.falhar("x"); }
		}
		return p;
	}

	/** Roda a acao em todo status fora de {@code permitidos}: todos devem recusar sem mexer no pedido. */
	private static void soNesses(EnumSet<StatusPedido> permitidos, Consumer<Pedido> acao) {
		for (var s : StatusPedido.values()) {
			if (permitidos.contains(s)) continue;
			var p = em(s, false);
			assertThatThrownBy(() -> acao.accept(p)).as("a partir de %s", s).isInstanceOf(TransicaoInvalidaException.class);
			assertThat(p.getStatus()).as("status inalterado").isEqualTo(s);
		}
	}

	@Test
	void nasceAguardandoPagamento() {
		var p = novo();
		assertThat(p.getStatus()).isEqualTo(AGUARDANDO_PAGAMENTO);
		assertThat(p.isGratuito()).isFalse();
		assertThat(p.getTentativas()).isZero();
		assertThat(p.getOracao()).isNull();
	}

	@Test
	void gratuitoNasceProntoParaGerar() {
		var p = Pedido.gratuito(new Pedido.Dados(7L, 1L, null, 0, "Ana", "ana@exemplo.com", "hm", null, null, null, null, null,
				"Paz", java.time.Instant.now(), null));
		assertThat(p.getStatus()).isEqualTo(PAGO);
		assertThat(p.isGratuito()).isTrue();
		assertThat(p.getValorCentavos()).isZero();
	}

	@Test
	void confirmarPagamento() {
		var p = novo();
		p.confirmarPagamento();
		assertThat(p.getStatus()).isEqualTo(PAGO);
		soNesses(EnumSet.of(AGUARDANDO_PAGAMENTO), Pedido::confirmarPagamento);
	}

	@Test
	void concederGratuidade() {
		var p = novo();
		p.concederGratuidade();
		assertThat(p.getStatus()).isEqualTo(PAGO);
		assertThat(p.isGratuito()).isTrue();
		assertThat(p.getValorCentavos()).isZero();
		soNesses(EnumSet.of(AGUARDANDO_PAGAMENTO), Pedido::concederGratuidade);
	}

	@Test
	void iniciarGeracaoNuncaDeAguardandoPagamento() {
		for (var s : new StatusPedido[] { PAGO, AGUARDANDO_NOVA_TENTATIVA, GERANDO_ORACAO }) {
			var p = em(s, false);
			p.iniciarGeracao();
			assertThat(p.getStatus()).isEqualTo(GERANDO_ORACAO);
		}
		soNesses(EnumSet.of(PAGO, AGUARDANDO_NOVA_TENTATIVA, GERANDO_ORACAO), Pedido::iniciarGeracao);
	}

	@Test
	void registrarOracao() {
		var p = em(GERANDO_ORACAO, false);
		p.registrarOracao("Senhor...");
		assertThat(p.getStatus()).isEqualTo(ORACAO_GERADA);
		assertThat(p.getOracao()).isEqualTo("Senhor...");
		soNesses(EnumSet.of(GERANDO_ORACAO), p2 -> p2.registrarOracao("x"));
		assertThatThrownBy(() -> em(GERANDO_ORACAO, false).registrarOracao(" ")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void agendarNovaTentativaContaETemErro() {
		var p = em(GERANDO_ORACAO, false);
		p.agendarNovaTentativa("timeout");
		assertThat(p.getStatus()).isEqualTo(AGUARDANDO_NOVA_TENTATIVA);
		assertThat(p.getTentativas()).isEqualTo(1);
		assertThat(p.getUltimoErro()).isEqualTo("timeout");
		soNesses(EnumSet.of(GERANDO_ORACAO), p2 -> p2.agendarNovaTentativa("x"));
	}

	@Test
	void falhar() {
		for (var s : new StatusPedido[] { PAGO, GERANDO_ORACAO, AGUARDANDO_NOVA_TENTATIVA, ORACAO_GERADA }) {
			var p = em(s, false);
			p.falhar("credit_balance");
			assertThat(p.getStatus()).isEqualTo(ERRO);
			assertThat(p.getUltimoErro()).isEqualTo("credit_balance");
		}
		soNesses(EnumSet.of(PAGO, GERANDO_ORACAO, AGUARDANDO_NOVA_TENTATIVA, ORACAO_GERADA), p -> p.falhar("x"));
	}

	@Test
	void marcarEnviadoEIdempotenteNoReenvio() {
		var p = em(ORACAO_GERADA, false);
		p.marcarEnviado();
		assertThat(p.getStatus()).isEqualTo(ENVIADA);
		p.marcarEnviado();
		assertThat(p.getStatus()).isEqualTo(ENVIADA);
		soNesses(EnumSet.of(ORACAO_GERADA, ENVIADA), Pedido::marcarEnviado);
	}

	@Test
	void solicitarReenvio() {
		var enviada = em(ENVIADA, true);
		enviada.solicitarReenvio();
		assertThat(enviada.getStatus()).isEqualTo(ENVIADA);
		var gerada = em(ORACAO_GERADA, true);
		gerada.solicitarReenvio();
		assertThat(gerada.getStatus()).isEqualTo(ORACAO_GERADA);
		var erro = em(ERRO, true);
		erro.solicitarReenvio();
		assertThat(erro.getStatus()).isEqualTo(ORACAO_GERADA);
		// ERRO sem oracao recusa no teste seguinte; os demais status recusam pelo status
		soNesses(EnumSet.of(ENVIADA, ORACAO_GERADA, ERRO), Pedido::solicitarReenvio);
	}

	@Test
	void solicitarReenvioDeErroSemOracaoRecusa() {
		assertThatThrownBy(() -> em(ERRO, false).solicitarReenvio()).isInstanceOf(TransicaoInvalidaException.class);
	}

	@Test
	void solicitarRegeneracaoSoDeErroSemOracao() {
		var p = em(ERRO, false);
		p.solicitarRegeneracao();
		assertThat(p.getStatus()).isEqualTo(PAGO);
		assertThat(p.getTentativas()).isZero();
		assertThat(p.getUltimoErro()).isNull();
		assertThatThrownBy(() -> em(ERRO, true).solicitarRegeneracao()).isInstanceOf(TransicaoInvalidaException.class);
		soNesses(EnumSet.of(ERRO), Pedido::solicitarRegeneracao);
	}

	@Test
	void transicaoInvalidaVira409() {
		assertThatThrownBy(() -> novo().iniciarGeracao()).isInstanceOfSatisfying(TransicaoInvalidaException.class,
				e -> assertThat(e.status().value()).isEqualTo(409));
	}
}
