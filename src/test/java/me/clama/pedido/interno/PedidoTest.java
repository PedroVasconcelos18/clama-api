package me.clama.pedido.interno;

import static me.clama.pedido.StatusPedido.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import me.clama.infra.cripto.HmacDeIdentificador;
import java.util.EnumSet;
import java.util.function.Consumer;
import me.clama.pedido.StatusPedido;
import me.clama.pedido.TransicaoInvalidaException;
import org.junit.jupiter.api.Test;

class PedidoTest {

	static Pedido novo() {
		return novo(1L, 2L);
	}

	static final HmacDeIdentificador HMAC = new HmacDeIdentificador("ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8=");
	static final Instant AGORA = Instant.parse("2026-11-11T12:00:00Z");

	static Pedido novo(Long planoId, Long instituicaoId) {
		return Pedido.novo(new Pedido.Dados(null, planoId, instituicaoId, 2000, "Maria", "maria@exemplo.com", "52998224725",
				"11999990000", (short) 30, "F", "Pela minha familia", AGORA, "203.0.113.9", "2026-01", null), HMAC, AGORA);
	}

	/** Leva o pedido ao status por caminhos legitimos, para testar a transicao a partir dele. */
	static Pedido em(StatusPedido alvo, boolean comOracao) {
		var p = novo();
		switch (alvo) {
			case AGUARDANDO_PAGAMENTO -> {}
			case PAGO -> p.confirmarPagamento(AGORA);
			case GERANDO_ORACAO -> { p.confirmarPagamento(AGORA); p.iniciarGeracao(AGORA); }
			case ORACAO_GERADA -> { p.confirmarPagamento(AGORA); p.iniciarGeracao(AGORA); p.registrarOracao("Senhor...", AGORA); }
			case ENVIADA -> { p.confirmarPagamento(AGORA); p.iniciarGeracao(AGORA); p.registrarOracao("Senhor...", AGORA); p.marcarEnviado(AGORA); }
			case AGUARDANDO_NOVA_TENTATIVA -> { p.confirmarPagamento(AGORA); p.iniciarGeracao(AGORA); p.agendarNovaTentativa("x", AGORA); }
			case AGUARDANDO_CONFIRMACAO_EMAIL -> p = freemium();
			case CONFIRMACAO_EXPIRADA -> { p = freemium(); p.expirarConfirmacao(AGORA); }
			case ERRO -> { p.confirmarPagamento(AGORA); p.iniciarGeracao(AGORA); if (comOracao) p.registrarOracao("Senhor...", AGORA); p.falhar("x", AGORA); }
		}
		return p;
	}

	static Pedido freemium() {
		return Pedido.freemium(new Pedido.Dados(null, 1L, null, 0, "Ana", "ana@exemplo.com", "52998224725", null, null, null,
				"Paz", AGORA, "203.0.113.9", "1", null), HMAC, AGORA);
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
		var p = Pedido.gratuito(new Pedido.Dados(7L, 1L, null, 0, "Ana", "ana@exemplo.com", null, null, null, null, "Paz", AGORA, null, "2026-01", null), HMAC, AGORA);
		assertThat(p.getStatus()).isEqualTo(PAGO);
		assertThat(p.isGratuito()).isTrue();
		assertThat(p.getValorCentavos()).isZero();
	}

	@Test
	void freemiumNasceAguardandoConfirmacaoGratuitoSemDono() {
		var p = freemium();
		assertThat(p.getStatus()).isEqualTo(AGUARDANDO_CONFIRMACAO_EMAIL);
		assertThat(p.isGratuito()).isTrue();
		assertThat(p.getValorCentavos()).isZero();
		assertThat(p.getUsuarioId()).isNull();
		assertThat(p.getEmailHmac()).isEqualTo(HMAC.email("ana@exemplo.com"));
		assertThat(p.getCpfHmac()).isEqualTo(HMAC.cpf("52998224725"));
		assertThatThrownBy(() -> Pedido.freemium(new Pedido.Dados(null, 1L, null, 5, "Ana", "a@e.com", null, null, null, null,
				"Paz", AGORA, null, "1", null), HMAC, AGORA)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void confirmarEmailVaiAPagoESoDoPendente() {
		var p = freemium();
		p.confirmarEmail(AGORA);
		assertThat(p.getStatus()).isEqualTo(PAGO);
		assertThat(p.isGratuito()).isTrue();
		soNesses(EnumSet.of(AGUARDANDO_CONFIRMACAO_EMAIL), ped -> ped.confirmarEmail(AGORA));
	}

	@Test
	void expirarConfirmacaoEhTerminalESoDoPendente() {
		var p = freemium();
		p.expirarConfirmacao(AGORA);
		assertThat(p.getStatus()).isEqualTo(CONFIRMACAO_EXPIRADA);
		soNesses(EnumSet.of(AGUARDANDO_CONFIRMACAO_EMAIL), ped -> ped.expirarConfirmacao(AGORA));
		// terminal: nem confirmar, nem gerar, nem regenerar, nem conceder
		assertThatThrownBy(() -> p.confirmarEmail(AGORA)).isInstanceOf(TransicaoInvalidaException.class);
		assertThatThrownBy(() -> p.iniciarGeracao(AGORA)).isInstanceOf(TransicaoInvalidaException.class);
		assertThatThrownBy(() -> p.solicitarRegeneracao(AGORA)).isInstanceOf(TransicaoInvalidaException.class);
		assertThatThrownBy(() -> p.concederGratuidade(AGORA)).isInstanceOf(TransicaoInvalidaException.class);
	}

	@Test
	void confirmarPagamento() {
		var p = novo();
		p.confirmarPagamento(AGORA);
		assertThat(p.getStatus()).isEqualTo(PAGO);
		soNesses(EnumSet.of(AGUARDANDO_PAGAMENTO), ped -> ped.confirmarPagamento(AGORA));
	}

	@Test
	void concederGratuidade() {
		var p = novo();
		p.concederGratuidade(AGORA);
		assertThat(p.getStatus()).isEqualTo(PAGO);
		assertThat(p.isGratuito()).isTrue();
		assertThat(p.getValorCentavos()).isZero();
		soNesses(EnumSet.of(AGUARDANDO_PAGAMENTO), ped -> ped.concederGratuidade(AGORA));
	}

	@Test
	void iniciarGeracaoNuncaDeAguardandoPagamento() {
		for (var s : new StatusPedido[] { PAGO, AGUARDANDO_NOVA_TENTATIVA, GERANDO_ORACAO }) {
			var p = em(s, false);
			p.iniciarGeracao(AGORA);
			assertThat(p.getStatus()).isEqualTo(GERANDO_ORACAO);
		}
		soNesses(EnumSet.of(PAGO, AGUARDANDO_NOVA_TENTATIVA, GERANDO_ORACAO), ped -> ped.iniciarGeracao(AGORA));
	}

	@Test
	void registrarOracao() {
		var p = em(GERANDO_ORACAO, false);
		p.registrarOracao("Senhor...", AGORA);
		assertThat(p.getStatus()).isEqualTo(ORACAO_GERADA);
		assertThat(p.getOracao()).isEqualTo("Senhor...");
		soNesses(EnumSet.of(GERANDO_ORACAO), p2 -> p2.registrarOracao("x", AGORA));
		assertThatThrownBy(() -> em(GERANDO_ORACAO, false).registrarOracao(" ", AGORA)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void agendarNovaTentativaContaETemErro() {
		var p = em(GERANDO_ORACAO, false);
		p.agendarNovaTentativa("timeout", AGORA);
		assertThat(p.getStatus()).isEqualTo(AGUARDANDO_NOVA_TENTATIVA);
		assertThat(p.getTentativas()).isEqualTo(1);
		assertThat(p.getUltimoErro()).isEqualTo("timeout");
		soNesses(EnumSet.of(GERANDO_ORACAO), p2 -> p2.agendarNovaTentativa("x", AGORA));
	}

	@Test
	void falhar() {
		for (var s : new StatusPedido[] { PAGO, GERANDO_ORACAO, AGUARDANDO_NOVA_TENTATIVA, ORACAO_GERADA }) {
			var p = em(s, false);
			p.falhar("credit_balance", AGORA);
			assertThat(p.getStatus()).isEqualTo(ERRO);
			assertThat(p.getUltimoErro()).isEqualTo("credit_balance");
		}
		soNesses(EnumSet.of(PAGO, GERANDO_ORACAO, AGUARDANDO_NOVA_TENTATIVA, ORACAO_GERADA), p -> p.falhar("x", AGORA));
	}

	@Test
	void marcarEnviadoEIdempotenteNoReenvio() {
		var p = em(ORACAO_GERADA, false);
		p.marcarEnviado(AGORA);
		assertThat(p.getStatus()).isEqualTo(ENVIADA);
		p.marcarEnviado(AGORA);
		assertThat(p.getStatus()).isEqualTo(ENVIADA);
		soNesses(EnumSet.of(ORACAO_GERADA, ENVIADA), ped -> ped.marcarEnviado(AGORA));
	}

	@Test
	void solicitarReenvio() {
		var enviada = em(ENVIADA, true);
		enviada.solicitarReenvio(AGORA);
		assertThat(enviada.getStatus()).isEqualTo(ENVIADA);
		var gerada = em(ORACAO_GERADA, true);
		gerada.solicitarReenvio(AGORA);
		assertThat(gerada.getStatus()).isEqualTo(ORACAO_GERADA);
		var erro = em(ERRO, true);
		erro.solicitarReenvio(AGORA);
		assertThat(erro.getStatus()).isEqualTo(ORACAO_GERADA);
		// ERRO sem oracao recusa no teste seguinte; os demais status recusam pelo status
		soNesses(EnumSet.of(ENVIADA, ORACAO_GERADA, ERRO), ped -> ped.solicitarReenvio(AGORA));
	}

	@Test
	void solicitarReenvioDeErroSemOracaoRecusa() {
		assertThatThrownBy(() -> em(ERRO, false).solicitarReenvio(AGORA)).isInstanceOf(TransicaoInvalidaException.class);
	}

	@Test
	void solicitarRegeneracaoSoDeErroSemOracao() {
		var p = em(ERRO, false);
		p.solicitarRegeneracao(AGORA);
		assertThat(p.getStatus()).isEqualTo(PAGO);
		assertThat(p.getTentativas()).isZero();
		assertThat(p.getUltimoErro()).isNull();
		assertThatThrownBy(() -> em(ERRO, true).solicitarRegeneracao(AGORA)).isInstanceOf(TransicaoInvalidaException.class);
		soNesses(EnumSet.of(ERRO), ped -> ped.solicitarRegeneracao(AGORA));
	}

	@Test
	void transicaoInvalidaVira409() {
		assertThatThrownBy(() -> novo().iniciarGeracao(AGORA)).isInstanceOfSatisfying(TransicaoInvalidaException.class,
				e -> assertThat(e.status().value()).isEqualTo(409));
	}

	@Test
	void hmacsSaemDaEntidade() {
		var p = novo();
		assertThat(p.getEmailHmac()).isEqualTo(HMAC.email("maria@exemplo.com"));
		assertThat(p.getCpfHmac()).isEqualTo(HMAC.cpf("52998224725"));
	}

	@Test
	void semCpfCpfHmacFicaNulo() {
		for (var cpf : new String[] { null, "", "  " }) {
			var p = Pedido.novo(new Pedido.Dados(null, 1L, null, 100, "A", "a@a.com", cpf, null, null, null, "t", AGORA, null, "2026-01", null), HMAC, AGORA);
			assertThat(p.getCpfHmac()).isNull();
		}
	}

	@Test
	void alterarEmailRecalculaOHmac() {
		var p = novo();
		var antes = p.getEmailHmac();
		p.alterarEmail("outra@exemplo.com", HMAC);
		assertThat(p.getEmail()).isEqualTo("outra@exemplo.com");
		assertThat(p.getEmailHmac()).isNotEqualTo(antes).isEqualTo(HMAC.email("outra@exemplo.com"));
	}

	@Test
	void ultimoErroEhCodigoCurto() {
		assertThatThrownBy(() -> em(GERANDO_ORACAO, false).falhar("x".repeat(61), AGORA)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> em(GERANDO_ORACAO, false).agendarNovaTentativa(" ", AGORA)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void transicaoUsaORelogioRecebido() {
		var p = novo();
		var depois = AGORA.plusSeconds(90);
		p.confirmarPagamento(depois);
		assertThat(p.getAtualizadoEm()).isEqualTo(depois);
		assertThat(p.getCriadoEm()).isEqualTo(AGORA);
	}

	@Test
	void toStringNaoVazaPii() {
		var d = new Pedido.Dados(null, 1L, null, 100, "Maria Secreta", "segredo@exemplo.com", "52998224725", "11999990000", null, null, "pedido intimo", AGORA, "203.0.113.9", "v", null);
		assertThat(d.toString()).doesNotContain("Secreta", "segredo", "52998224725", "intimo", "203.0");
		var cob = new me.clama.pedido.PedidoCobravel(java.util.UUID.randomUUID(), AGORA_STATUS, 1, "Maria Secreta", "segredo@exemplo.com", "52998224725");
		assertThat(cob.toString()).doesNotContain("Secreta", "segredo", "52998224725");
		var dados = new me.clama.pedido.DadosParaOracao(java.util.UUID.randomUUID(), AGORA_STATUS, 1L, false, "Maria Secreta", "segredo@exemplo.com", null, null, "pedido intimo", 7L, null, "oracao intima", 0, null);
		assertThat(dados.toString()).doesNotContain("Secreta", "segredo", "intimo");
	}

	private static final StatusPedido AGORA_STATUS = PAGO;

	@Test
	void pagoEmNasTresConfirmacoes() {
		var pago = novo();
		pago.confirmarPagamento(AGORA);
		var gratis = novo();
		gratis.concederGratuidade(AGORA);
		var freemium = freemium();
		freemium.confirmarEmail(AGORA);

		assertThat(pago.getPagoEm()).isEqualTo(AGORA);
		assertThat(gratis.getPagoEm()).isEqualTo(AGORA);
		assertThat(freemium.getPagoEm()).isEqualTo(AGORA);
		assertThat(novo().getPagoEm()).isNull();
		assertThat(freemium().getPagoEm()).isNull();
	}

	@Test
	void enviadaEmSoNoPrimeiroEnvio() {
		var p = em(ORACAO_GERADA, true);
		assertThat(p.getEnviadaEm()).isNull();
		p.marcarEnviado(AGORA);
		p.marcarEnviado(AGORA.plusSeconds(3600)); // reenvio

		assertThat(p.getEnviadaEm()).isEqualTo(AGORA);
		assertThat(p.getAtualizadoEm()).isEqualTo(AGORA.plusSeconds(3600));
	}
}
