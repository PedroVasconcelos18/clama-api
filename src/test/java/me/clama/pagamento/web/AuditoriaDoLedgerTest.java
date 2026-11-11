package me.clama.pagamento.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import me.clama.conta.interno.Papel;
import me.clama.conta.web.Navegador;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Auditoria do ledger (D7, INV:159): quatro achados; consistente = vazio; gratuito e sem instituicao nao sao orfaos. */
class AuditoriaDoLedgerTest extends PagamentoAdminBase {

	JsonNode auditar() throws Exception {
		var res = entrar(Papel.ADMIN).get("/api/admin/repasses/auditoria");
		assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
		return json(res);
	}

	List<String> tipos(JsonNode j) {
		var l = new java.util.ArrayList<String>();
		j.get("achados").forEach(a -> l.add(a.get("tipo").asString()));
		return l;
	}

	@Test
	void anonimoRecebe401EClienteRecebe403() throws Exception {
		var anonimo = new Navegador(porta);
		anonimo.get("/api/auth/eu");
		assertThat(anonimo.get("/api/admin/repasses/auditoria").statusCode()).isEqualTo(401);
		assertThat(entrar(Papel.CLIENTE).get("/api/admin/repasses/auditoria").statusCode()).isEqualTo(403);
	}

	@Test
	void ledgerConsistenteDaListaVazia() throws Exception {
		var lar = instituicao("Lar");
		var p = pedido(3000, lar);
		paga(p, 100L);
		repasse(p, lar, "REGISTRADO");
		var q = pedido(3000, lar);
		paga(q, 101L);
		repasse(q, lar, "TRANSFERIDO");
		var cancelado = pedido(3000, lar); // estorno: sem cobranca paga a mais e nada a reportar
		repasse(cancelado, lar, "CANCELADO");
		var j = auditar();
		assertThat(j.get("achados")).isEmpty();
		assertThat(j.get("criticos").asInt()).isZero();
		assertThat(j.get("emJogoCentavos").asInt()).isZero();
	}

	@Test
	void ledgerVazioTambemEConsistente() throws Exception {
		assertThat(auditar().get("achados")).isEmpty();
	}

	@Test
	void orfaoECobrancaPagaComInstituicaoENaoGratuitoSemRepasse() throws Exception {
		var lar = instituicao("Lar Esperança");
		var p = pedido(3001, lar);
		paga(p, 100L);
		var j = auditar();
		assertThat(j.get("achados")).hasSize(1);
		var a = j.get("achados").get(0);
		assertThat(a.get("tipo").asString()).isEqualTo("orfao");
		assertThat(a.get("gravidade").asString()).isEqualTo("CRITICO");
		assertThat(a.get("instituicao").asString()).isEqualTo("Lar Esperança");
		assertThat(a.get("pedidoId").asString()).isEqualTo(p.toString());
		assertThat(a.get("valorEmJogoCentavos").asInt()).isEqualTo(600); // 20% de 3001, para baixo
		assertThat(j.get("criticos").asInt()).isEqualTo(1);
		assertThat(j.get("emJogoCentavos").asInt()).isEqualTo(600);
	}

	@Test
	void orfaoUsaOPercentualDaConfiguracaoENaoUm20Fixo() throws Exception {
		// com o repasse que daria 0 (valor de 4 centavos a 20% = 0) nao ha o que dever: nao e orfao
		var lar = instituicao("Lar");
		var p = pedido(4, lar);
		paga(p, 100L);
		assertThat(auditar().get("achados")).isEmpty();
	}

	@Test
	void gratuitoESemInstituicaoNaoSaoOrfaos() throws Exception {
		var lar = instituicao("Lar");
		var gratuito = pedido(0, lar); // o banco so aceita gratuito com valor 0
		jdbc.update("update pedido set gratuito = true where id = ?", gratuito);
		paga(gratuito, 100L);
		var zero = pedido(0, lar);
		paga(zero, 101L);
		var semInstituicao = pedido(3000, null);
		paga(semInstituicao, 102L);
		var naoPago = pedido(3000, lar); // sem cobranca paga nem repasse: aguardando pagamento, nada a ver com o ledger
		cobranca(naoPago, 1, 103L);
		assertThat(auditar().get("achados")).isEmpty();
	}

	@Test
	void pedidoNaoPagoERepasseVivoSemCobrancaPaga() throws Exception {
		var lar = instituicao("Lar");
		var registrado = pedido(3000, lar);
		repasse(registrado, lar, "REGISTRADO");
		var transferido = pedido(3000, lar);
		cobranca(transferido, 1, 100L); // cobranca criada mas nao paga
		repasse(transferido, lar, "TRANSFERIDO");
		var cancelado = pedido(3000, lar);
		repasse(cancelado, lar, "CANCELADO"); // estornado: nao e achado
		var j = auditar();
		assertThat(tipos(j)).containsExactly("pedido_nao_pago", "pedido_nao_pago");
		assertThat(j.get("achados").get(0).get("gravidade").asString()).isEqualTo("CRITICO");
		assertThat(j.get("emJogoCentavos").asInt()).isEqualTo(1200);
		assertThat(j.toString()).doesNotContain(cancelado.toString());
	}

	@Test
	void calculoErradoTemADiferencaComoValorEmJogo() throws Exception {
		var lar = instituicao("Lar");
		var p = pedido(3000, lar);
		paga(p, 100L);
		repasse(p, lar, "REGISTRADO");
		jdbc.update("update repasse set valor_centavos = 500 where pedido_id = ?", p);
		var j = auditar();
		assertThat(tipos(j)).containsExactly("calculo_errado");
		var a = j.get("achados").get(0);
		assertThat(a.get("valorEmJogoCentavos").asInt()).isEqualTo(100);
		assertThat(a.get("detalhe").asString()).isEqualTo("ledger diz 500 centavos; 20% de 3000 dá 600");
	}

	@Test
	void divergenciaDeBaseEAtencaoESemValorEmJogo() throws Exception {
		var lar = instituicao("Lar");
		var p = pedido(3000, lar);
		paga(p, 100L);
		repasse(p, lar, "REGISTRADO");
		jdbc.update("update pedido set valor_centavos = 2000 where id = ?", p);
		var j = auditar();
		assertThat(tipos(j)).containsExactly("divergencia_de_base");
		assertThat(j.get("achados").get(0).get("gravidade").asString()).isEqualTo("ATENCAO");
		assertThat(j.get("achados").get(0).get("valorEmJogoCentavos").asInt()).isZero();
		assertThat(j.get("criticos").asInt()).isZero();
		assertThat(j.get("emJogoCentavos").asInt()).isZero();
	}

	@Test
	void criticosAntesDeAtencaoSemPiiNoDetalhe() throws Exception {
		var lar = instituicao("Lar");
		var divergente = pedido("Zuleica Quintanilha", 3000, lar);
		paga(divergente, 100L);
		repasse(divergente, lar, "REGISTRADO");
		jdbc.update("update pedido set valor_centavos = 2000 where id = ?", divergente);
		var orfao = pedido("Maria Souza", 3000, lar);
		paga(orfao, 101L);
		var j = auditar();
		assertThat(tipos(j)).containsExactly("orfao", "divergencia_de_base");
		assertThat(j.get("criticos").asInt()).isEqualTo(1);
		assertThat(j.toString()).doesNotContain("Zuleica", "Maria", "@", "52998224725", "11987654321");
	}
}
