package me.clama.pedido.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import me.clama.pedido.StatusPedido;
import org.junit.jupiter.api.Test;

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

	@Test
	void semLimiteDeQuantidadeAlemDosLimitesDeTaxa() throws Exception { // D18: cinco na hora passam
		var u = logado();
		for (int i = 0; i < 5; i++) assertThat(u.nav().post("/api/pedidos/gratuito", json(corpo())).statusCode()).isEqualTo(201);
		assertThat(capturador.geracoes).hasSize(5);
		assertThat(u.nav().post("/api/pedidos/gratuito", json(corpo())).statusCode()).isEqualTo(429);
	}
}
