package me.clama.pedido.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import me.clama.EmailDeTeste;
import me.clama.pedido.StatusPedido;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@org.springframework.context.annotation.Import(EmailDeTeste.class)
class PedidoGratuitoTest extends PedidoWebTeste {

	@Test
	void anonimoRecebe401() throws Exception {
		assertThat(anonimo().post("/api/pedidos/gratuito", json(corpo())).statusCode()).isEqualTo(401);
	}

	@Test
	void criaPagoGratuitoPublicaGeracaoSolicitada() throws Exception {
		var u = logado();
		var simples = plano("Simples", 3000);
		var inst = instituicao("Casa", true);

		var res = u.nav().post("/api/pedidos/gratuito", json(com(corpo(), "plano", simples, "instituicao", inst, "valorCentavos", 9999)));

		assertThat(res.statusCode()).isEqualTo(201);
		var id = UUID.fromString(corpo(res).get("id").asString());
		assertThat(corpo(res).get("status").asString()).isEqualTo("PAGO");
		assertThat(corpo(res).get("valorCentavos").asLong()).isZero();
		var p = pedidos.findById(id).orElseThrow();
		assertThat(p.getStatus()).isEqualTo(StatusPedido.PAGO);
		assertThat(p.isGratuito()).isTrue();
		assertThat(p.getValorCentavos()).isZero();
		assertThat(p.getPlanoId()).isEqualTo(planoTecnico("GRATUITO")); // plano, instituicao e valor do corpo sao ignorados
		assertThat(p.getInstituicaoId()).isNull();
		assertThat(p.getUsuarioId()).isEqualTo(u.id());
		assertThat(p.getConsentimentoVersao()).isEqualTo("1");
		assertThat(capturador.geracoes).containsExactly(id);
	}

	@Test
	void pedidoGratuitoGuardaOTemaEAContaDevolve() throws Exception {
		var u = logado();

		var res = u.nav().post("/api/pedidos/gratuito", json(com(corpo(), "tema", "fe")));

		assertThat(res.statusCode()).isEqualTo(201);
		assertThat(corpo(u.nav().get("/api/conta/pedidos")).get(0).get("tema").asString()).isEqualTo("fe");
	}

	@Test
	void semPlanoGratuito503ENadaGravado() throws Exception {
		var u = logado();
		jdbc.update("delete from plano where tipo = 'GRATUITO'"); // o @AfterEach recoloca
		var res = u.nav().post("/api/pedidos/gratuito", json(corpo()));
		assertThat(res.statusCode()).isEqualTo(503);
		assertThat(corpo(res).get("codigo").asString()).isEqualTo("freemium_indisponivel");
		assertThat(pedidos.count()).isZero();
		assertThat(capturador.geracoes).isEmpty();
	}

	@Test
	void camposInvalidos400AntesDoPlano() throws Exception {
		var u = logado();
		jdbc.update("delete from plano where tipo = 'GRATUITO'");
		var res = u.nav().post("/api/pedidos/gratuito", json(com(corpo(), "nome", "A")));
		assertThat(res.statusCode()).isEqualTo(400);
	}

	@Autowired EmailDeTeste.RelogioAvancavel relogioDeTeste;

	@AfterEach
	void zerarRelogio() { relogioDeTeste.zerar(); }

	@Test
	void segundoGratuitoEm24hE429() throws Exception {
		var u = logado();
		assertThat(u.nav().post("/api/pedidos/gratuito", json(corpo())).statusCode()).isEqualTo(201);

		relogioDeTeste.avancar(Duration.ofHours(23).plusMinutes(59));
		var res = u.nav().post("/api/pedidos/gratuito", json(corpo()));
		assertThat(res.statusCode()).isEqualTo(429);
		assertThat(corpo(res).get("codigo").asString()).isEqualTo("gratuito_diario");
		assertThat(corpo(res).get("mensagemPastoral").asString())
				.isEqualTo("Você já recebeu uma oração gratuita nas últimas 24 horas. Volte amanhã para pedir outra — estamos com você.");
		assertThat(pedidos.count()).isEqualTo(1);

		relogioDeTeste.avancar(Duration.ofMinutes(2)); // +24h01
		assertThat(u.nav().post("/api/pedidos/gratuito", json(corpo())).statusCode()).isEqualTo(201);
	}

	@Test
	void pedidoPagoNaoContaParaOLimiteGratuito() throws Exception {
		var u = logado();
		var inst = instituicao("Casa", true);
		assertThat(u.nav().post("/api/pedidos", json(com(corpo(), "instituicao", inst, "valorCentavos", 3000))).statusCode()).isEqualTo(201);
		assertThat(u.nav().post("/api/pedidos/gratuito", json(corpo())).statusCode()).isEqualTo(201);
	}
}
