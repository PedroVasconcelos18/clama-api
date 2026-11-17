package me.clama.pedido.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import me.clama.conta.web.Navegador;
import me.clama.pedido.StatusPedido;
import org.junit.jupiter.api.Test;

class CriarPedidoTest extends PedidoWebTeste {

	@Test
	void anonimoRecebe401() throws Exception {
		var res = anonimo().post("/api/pedidos", json(corpo()));
		assertThat(res.statusCode()).isEqualTo(401);
		assertThat(corpo(res).get("codigo").asString()).isEqualTo("nao_autenticado");
	}

	@Test
	void semCsrfRecebe403() throws Exception {
		var u = logado();
		var semCookie = new Navegador(porta); // sem GET antes: sem XSRF-TOKEN
		assertThat(semCookie.post("/api/pedidos", json(corpo())).statusCode()).isEqualTo(403);
		assertThat(pedidos.count()).isZero();
	}

	@Test
	void criaPedidoPagoAguardandoPagamentoSemPix() throws Exception {
		var u = logado();
		var plano = plano("Simples", 3000);
		var inst = instituicao("Casa de Apoio", true);

		var res = u.nav().post("/api/pedidos", json(com(corpo(), "plano", plano, "instituicao", inst, "usuario", 999, "valorCentavos", 3500)));

		assertThat(res.statusCode()).isEqualTo(201);
		var c = corpo(res);
		assertThat(c.get("status").asString()).isEqualTo("AGUARDANDO_PAGAMENTO");
		assertThat(c.get("valorCentavos").asLong()).isEqualTo(3500);
		assertThat(c.get("instituicao").asLong()).isEqualTo(inst);
		assertThat(c.get("criadoEm").asString()).endsWith("Z");
		assertThat(c.has("pix") || c.has("pixQrCode")).isFalse();
		var p = pedidos.findById(java.util.UUID.fromString(c.get("id").asString())).orElseThrow();
		assertThat(p.getUsuarioId()).isEqualTo(u.id()); // o "usuario" do corpo e ignorado
		assertThat(p.getPlanoId()).isEqualTo(plano);
		assertThat(p.getStatus()).isEqualTo(StatusPedido.AGUARDANDO_PAGAMENTO);
		assertThat(p.getNome()).isEqualTo("Maria Souza");
		assertThat(p.getCpfCnpj()).isEqualTo("52998224725");
		assertThat(p.getConsentimentoVersao()).isEqualTo("1");
		assertThat(p.getConsentimentoIp()).isNotBlank();
		assertThat(p.getConsentidoEm()).isNotNull();
		assertThat(capturador.geracoes).isEmpty();
	}

	@Test
	void pedidoPagoSemInstituicaoE400() throws Exception {
		var u = logado();
		var msg = "Escolha a instituição que vai receber parte da sua contribuição.";
		var res = u.nav().post("/api/pedidos", json(com(corpo(), "valorCentavos", 3000)));
		assertThat(res.statusCode()).isEqualTo(400);
		assertThat(corpo(res).get("erros").get(0).get("campo").asString()).isEqualTo("instituicao");
		assertThat(corpo(res).get("erros").get(0).get("mensagem").asString()).isEqualTo(msg);
	}

	@Test
	void semPlanoInfereOPlanoPeloValor() throws Exception {
		var inst = instituicao("Casa", true);
		var u = logado();
		plano("Simples", 3000);
		var maior = plano("Completo", 9000);
		var res = u.nav().post("/api/pedidos", json(com(corpo(), "instituicao", inst, "valorCentavos", 9500)));
		assertThat(res.statusCode()).isEqualTo(201);
		assertThat(pedidos.findById(java.util.UUID.fromString(corpo(res).get("id").asString())).orElseThrow().getPlanoId()).isEqualTo(maior);
	}

	@Test
	void planoExplicitoExigeValorDoPlano() throws Exception { // D6
		var u = logado();
		var inst = instituicao("Casa", true);
		var plano = plano("Simples", 3000);
		var res = u.nav().post("/api/pedidos", json(com(corpo(), "instituicao", inst, "plano", plano, "valorCentavos", 2999)));
		assertThat(res.statusCode()).isEqualTo(400);
		assertThat(erroDe(res, "valorCentavos")).isEqualTo("O valor mínimo para este plano é R$ 30,00.");
		assertThat(pedidos.count()).isZero();
		assertThat(u.nav().post("/api/pedidos", json(com(corpo(), "instituicao", inst, "plano", plano, "valorCentavos", 3000))).statusCode()).isEqualTo(201);
	}

	@Test
	void pisoDeUmReal() throws Exception { // D7
		var u = logado();
		var inst = instituicao("Casa", true);
		plano("Simples", 3000);
		var res = u.nav().post("/api/pedidos", json(com(corpo(), "instituicao", inst, "valorCentavos", 99)));
		assertThat(res.statusCode()).isEqualTo(400);
		assertThat(erroDe(res, "valorCentavos")).isEqualTo("O valor mínimo é R$ 1,00 — qualquer oferta acima ajuda o Clama.");
		assertThat(u.nav().post("/api/pedidos", json(com(corpo(), "instituicao", inst, "valorCentavos", 100))).statusCode()).isEqualTo(201);
	}

	@Test
	void planoInexistenteInativoOuTecnicoNaoEstaDisponivel() throws Exception {
		var u = logado();
		var inativo = plano("Antigo", 3000);
		jdbc.update("update plano set ativo = false where id = ?", inativo);
		for (Object id : new Object[] { inativo, 987654321L, planoTecnico("VALOR_LIVRE"), planoTecnico("GRATUITO") }) {
			var res = u.nav().post("/api/pedidos", json(com(corpo(), "plano", id)));
			assertThat(res.statusCode()).as(id.toString()).isEqualTo(400);
			assertThat(erroDe(res, "plano")).isEqualTo("Esse plano não está disponível no momento.");
		}
	}

	@Test
	void instituicaoInexistenteOuInativa() throws Exception {
		var u = logado();
		plano("Simples", 3000);
		var inativa = instituicao("Fechada", false);
		for (Object id : new Object[] { inativa, 987654321L }) {
			var res = u.nav().post("/api/pedidos", json(com(corpo(), "instituicao", id)));
			assertThat(res.statusCode()).isEqualTo(400);
			assertThat(erroDe(res, "instituicao")).isEqualTo("Não encontramos essa instituição. Escolha uma da lista, por favor.");
		}
	}

	@Test
	void camposInvalidosComAMensagemDoInventario() throws Exception {
		var u = logado();
		var ruim = Map.<String, Object>ofEntries(Map.entry("nome", "A"), Map.entry("email", "sem-arroba"), Map.entry("cpfCnpj", "11111111111"),
				Map.entry("idade", 0), Map.entry("sexo", "x"), Map.entry("consentAceito", false), Map.entry("valorCentavos", 3000));
		var res = u.nav().post("/api/pedidos", json(ruim));
		assertThat(res.statusCode()).isEqualTo(400);
		assertThat(corpo(res).get("codigo").asString()).isEqualTo("dados_invalidos");
		assertThat(erroDe(res, "nome")).isEqualTo("Conta um pouco de você no nome — pelo menos 2 letras.");
		assertThat(erroDe(res, "email")).isEqualTo("Confira seu e-mail — parece que faltou algo.");
		assertThat(erroDe(res, "cpfCnpj")).isEqualTo("Confira seu CPF — parece que tem algum dígito errado.");
		assertThat(erroDe(res, "idade")).isNotNull();
		assertThat(erroDe(res, "sexo")).isNotNull();
		assertThat(erroDe(res, "consentAceito")).isEqualTo("Para enviar seu pedido, é preciso concordar com a política de privacidade.");
	}

	@Test
	void obrigatoriosAusentes() throws Exception {
		var u = logado();
		var res = u.nav().post("/api/pedidos", "{}");
		assertThat(erroDe(res, "nome")).isEqualTo("Por favor, digite seu nome.");
		assertThat(erroDe(res, "email")).isEqualTo("Por favor, digite seu e-mail.");
		assertThat(erroDe(res, "cpfCnpj")).isNotNull();
		assertThat(erroDe(res, "consentAceito")).isEqualTo("Para enviar seu pedido, é preciso concordar com a política de privacidade.");
	}

	@Test
	void todaStringTemTeto() throws Exception {
		var u = logado();
		var longo = "x".repeat(2001);
		var res = u.nav().post("/api/pedidos", json(com(corpo(), "nome", "n".repeat(121), "email", "e".repeat(250) + "@a.com",
				"telefone", "1".repeat(21), "pedidoOracao", longo, "sexo", "feminino".repeat(3))));
		assertThat(res.statusCode()).isEqualTo(400);
		for (var campo : new String[] { "nome", "email", "telefone", "pedidoOracao", "sexo" })
			assertThat(erroDe(res, campo)).as(campo).isNotNull();
	}

	@Test
	void limiteDeCincoPorHoraPorUsuario() throws Exception { // INV:23: 10/min e 5/h; o da hora e o que aperta
		var u = logado();
		plano("Simples", 3000);
		var inst = instituicao("Casa", true);
		for (int i = 0; i < 5; i++) assertThat(u.nav().post("/api/pedidos", json(com(corpo(), "instituicao", inst))).statusCode()).isEqualTo(201);
		var res = u.nav().post("/api/pedidos", json(com(corpo(), "instituicao", inst)));
		assertThat(res.statusCode()).isEqualTo(429);
		assertThat(corpo(res).get("codigo").asString()).isEqualTo("limite_excedido");
		assertThat(pedidos.count()).isEqualTo(5);
	}
}
