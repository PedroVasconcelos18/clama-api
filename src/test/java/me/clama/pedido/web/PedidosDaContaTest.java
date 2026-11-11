package me.clama.pedido.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import me.clama.conta.web.Navegador;
import org.junit.jupiter.api.Test;

class PedidosDaContaTest extends PedidoWebTeste {

	private java.util.List<String> ids(java.net.http.HttpResponse<String> res) {
		var l = new ArrayList<String>();
		corpo(res).forEach(n -> l.add(n.get("id").asString()));
		return l;
	}

	@Test
	void anonimoRecebe401() throws Exception {
		assertThat(new Navegador(porta).get("/api/conta/pedidos").statusCode()).isEqualTo(401);
	}

	@Test
	void soOsDoUsuarioMaisRecentesPrimeiro() throws Exception {
		var u = logado();
		var outro = logado();
		var plano = plano("Simples", 3000);
		var velho = pedido(u.id(), plano, Instant.parse("2026-03-01T12:00:00Z"));
		var novo = pedido(u.id(), plano, Instant.parse("2026-03-02T12:00:00Z"));
		pedido(outro.id(), plano, Instant.parse("2026-03-02T12:00:00Z"));
		pedido(null, plano, Instant.parse("2026-03-02T12:00:00Z"));

		var res = u.nav().get("/api/conta/pedidos");

		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(ids(res)).containsExactly(novo.getId().toString(), velho.getId().toString());
		var item = corpo(res).get(0);
		assertThat(item.get("plano").asString()).isEqualTo("Simples");
		assertThat(item.get("valorCentavos").asLong()).isEqualTo(3000);
		assertThat(item.get("gratuito").asBoolean()).isFalse();
		assertThat(item.get("criadoEm").asString()).isEqualTo("2026-03-02T12:00:00Z");
		assertThat(item.get("oracaoGerada").isNull()).isTrue();
		assertThat(res.body()).doesNotContain("Maria").doesNotContain("maria@");
	}

	@Test
	void oracaoSoQuandoEnviada() throws Exception {
		var u = logado();
		var p = pedido(u.id(), plano("Simples", 3000), relogio.instant());
		var t = relogio.instant();
		p.confirmarPagamento(t);
		p.iniciarGeracao(t);
		p.registrarOracao("Texto da oração", t);
		salvar(p);
		assertThat(corpo(u.nav().get("/api/conta/pedidos")).get(0).get("oracaoGerada").isNull()).isTrue();
		p.marcarEnviado(t);
		salvar(p);
		assertThat(corpo(u.nav().get("/api/conta/pedidos")).get(0).get("oracaoGerada").asString()).isEqualTo("Texto da oração");
	}

	@Test
	void diasDeSaoPauloInclusivos() throws Exception {
		var u = logado();
		var plano = plano("Simples", 3000);
		var antes = pedido(u.id(), plano, Instant.parse("2026-03-10T02:59:59Z")); // 23:59:59 do dia 9 em SP
		var depois = pedido(u.id(), plano, Instant.parse("2026-03-10T03:00:00Z")); // 00:00:00 do dia 10 em SP
		var fim = pedido(u.id(), plano, Instant.parse("2026-03-11T02:59:59Z")); // 23:59:59 do dia 10 em SP
		var seguinte = pedido(u.id(), plano, Instant.parse("2026-03-11T03:00:00Z"));

		assertThat(ids(u.nav().get("/api/conta/pedidos?from=2026-03-10&to=2026-03-10")))
				.containsExactly(fim.getId().toString(), depois.getId().toString());
		assertThat(ids(u.nav().get("/api/conta/pedidos?to=2026-03-09"))).containsExactly(antes.getId().toString());
		assertThat(ids(u.nav().get("/api/conta/pedidos?from=2026-03-11"))).containsExactly(seguinte.getId().toString());
	}

	@Test
	void dataInvalidaEIgnorada() throws Exception {
		var u = logado();
		pedido(u.id(), plano("Simples", 3000), relogio.instant());
		assertThat(ids(u.nav().get("/api/conta/pedidos?from=ontem&to=31-12-2026"))).hasSize(1);
	}
}
