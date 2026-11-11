package me.clama.pedido.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import me.clama.conta.web.Navegador;
import me.clama.infra.cripto.Cifra;
import me.clama.pedido.interno.Pedido;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class StatusPublicoTest extends PedidoWebTeste {

	@MockitoSpyBean Cifra cifra;

	private Pedido enviado(Long plano) {
		var p = pedido(null, plano, relogio.instant());
		var agora = relogio.instant();
		p.confirmarPagamento(agora);
		p.iniciarGeracao(agora);
		p.registrarOracao("Senhor, guarda esta família.", agora);
		return p;
	}

	@Test
	void aguardandoPagamentoSemOracaoENemDadosPessoais() throws Exception {
		var plano = plano("Simples", 3000);
		var p = pedido(null, plano, relogio.instant());

		var res = new Navegador(porta).get("/api/pedidos/" + p.getId());

		assertThat(res.statusCode()).isEqualTo(200);
		var c = corpo(res);
		var campos = new ArrayList<String>();
		c.propertyNames().forEach(campos::add);
		assertThat(campos).containsExactlyInAnyOrder("id", "status", "plano", "instituicao", "valorCentavos", "criadoEm",
				"mensagemPastoral", "oracaoGerada");
		assertThat(c.get("status").asString()).isEqualTo("AGUARDANDO_PAGAMENTO");
		assertThat(c.get("plano").asString()).isEqualTo("Simples");
		assertThat(c.get("valorCentavos").asLong()).isEqualTo(3000);
		assertThat(c.get("criadoEm").asString()).endsWith("Z");
		assertThat(c.get("oracaoGerada").isNull()).isTrue();
		assertThat(res.body()).doesNotContain("Maria").doesNotContain("maria@").doesNotContain("52998224725");
		assertThat(res.headers().allValues("Set-Cookie")).isEmpty(); // publico, sem sessao nem XSRF
	}

	@Test
	void oracaoSoApareceEmEnviada() throws Exception {
		var plano = plano("Simples", 3000);
		var p = salvar(enviado(plano));
		assertThat(corpo(new Navegador(porta).get("/api/pedidos/" + p.getId())).get("oracaoGerada").isNull()).isTrue(); // ORACAO_GERADA
		p.marcarEnviado(relogio.instant());
		salvar(p);
		var c = corpo(new Navegador(porta).get("/api/pedidos/" + p.getId()));
		assertThat(c.get("status").asString()).isEqualTo("ENVIADA");
		assertThat(c.get("oracaoGerada").asString()).isEqualTo("Senhor, guarda esta família.");
	}

	@Test
	void soDecifraAOracaoQuandoEnviada() throws Exception {
		var plano = plano("Simples", 3000);
		var p = salvar(enviado(plano)); // ORACAO_GERADA
		clearInvocations(cifra);
		assertThat(new Navegador(porta).get("/api/pedidos/" + p.getId()).statusCode()).isEqualTo(200);
		verify(cifra, never()).decifrar(anyString()); // nem nome, e-mail, CPF nem a oracao

		p.marcarEnviado(relogio.instant());
		salvar(p);
		clearInvocations(cifra);
		assertThat(corpo(new Navegador(porta).get("/api/pedidos/" + p.getId())).get("oracaoGerada").asString()).isNotBlank();
		verify(cifra, times(1)).decifrar(anyString()); // so a oracao
	}

	@Test
	void erroPorCreditosMostraAMensagemDeVinteEQuatroHoras() throws Exception {
		var plano = plano("Simples", 3000);
		var p = pedido(null, plano, relogio.instant());
		p.confirmarPagamento(relogio.instant());
		p.falhar("credit_balance", relogio.instant());
		salvar(p);
		var c = corpo(new Navegador(porta).get("/api/pedidos/" + p.getId()));
		assertThat(c.get("status").asString()).isEqualTo("ERRO");
		assertThat(c.get("mensagemPastoral").asString()).isEqualTo("Recebemos seu pedido com carinho. Sua oração será preparada e enviada em até 24 horas. Se precisar falar com alguém antes, escreva pra contato@clama.me.");
	}

	@Test
	void outroErroNaoDizNada() throws Exception {
		var plano = plano("Simples", 3000);
		var p = pedido(null, plano, relogio.instant());
		p.confirmarPagamento(relogio.instant());
		p.falhar("pedido_preso", relogio.instant());
		salvar(p);
		assertThat(corpo(new Navegador(porta).get("/api/pedidos/" + p.getId())).get("mensagemPastoral").isNull()).isTrue();
	}

	@Test
	void inexistenteEMalformadoDao404Pastoral() throws Exception {
		for (var id : new String[] { UUID.randomUUID().toString(), "nao-e-uuid", "123" }) {
			var res = new Navegador(porta).get("/api/pedidos/" + id);
			assertThat(res.statusCode()).as(id).isEqualTo(404);
			assertThat(corpo(res).get("mensagemPastoral").asString()).isEqualTo("Não encontramos esse pedido. Pode tentar novamente?");
		}
	}

	@Test
	void centoEVinteConsultasPorMinutoPorIp() throws Exception { // a recarga e gradual: ~1 ficha a cada 0,5 s
		var nav = new Navegador(porta);
		var id = UUID.randomUUID();
		int passaram = 0;
		while (passaram < 200 && nav.get("/api/pedidos/" + id).statusCode() == 404) passaram++;
		assertThat(passaram).isBetween(120, 130);
		assertThat(nav.get("/api/pedidos/" + id).statusCode()).isEqualTo(429);
	}

	@Test
	void soLeituraNaCadeiaPublica() throws Exception {
		var res = anonimo().post("/api/pedidos/" + UUID.randomUUID(), "{}");
		assertThat(res.statusCode()).isEqualTo(401); // POST cai na cadeia padrao (autenticada): sem sessao, 401; so o GET e publico
	}
}
