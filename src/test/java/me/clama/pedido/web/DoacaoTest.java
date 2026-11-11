package me.clama.pedido.web;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import me.clama.conta.web.Navegador;
import me.clama.pedido.StatusPedido;
import org.junit.jupiter.api.Test;

class DoacaoTest extends PedidoWebTeste {

	static final String CAPTCHA = "Verificação anti-robô falhou. Atualize a página e tente de novo.";

	private java.util.Map<String, Object> doacao() {
		return com(corpo(), "turnstileToken", "token-ok", "deviceHash", "abcdef1234567890");
	}

	@Test
	void doacaoAnonimaCriaPedidoSemDonoEInfereOPlano() throws Exception {
		turnstileAprova(true);
		var simples = plano("Simples", 3000);
		var outro = plano("Completo", 9000);

		var res = anonimo().post("/api/doacoes", json(com(doacao(), "plano", outro, "valorCentavos", 3500)));

		assertThat(res.statusCode()).isEqualTo(201);
		var p = pedidos.findById(UUID.fromString(corpo(res).get("id").asString())).orElseThrow();
		assertThat(corpo(res).get("status").asString()).isEqualTo("AGUARDANDO_PAGAMENTO");
		assertThat(p.getStatus()).isEqualTo(StatusPedido.AGUARDANDO_PAGAMENTO);
		assertThat(p.getUsuarioId()).isNull();
		assertThat(p.getPlanoId()).isEqualTo(simples); // o "plano" do corpo e ignorado
		assertThat(p.getConsentimentoVersao()).isEqualTo("1");
		assertThat(p.getConsentimentoIp()).isNotBlank();
		TURNSTILE.verify(postRequestedFor(urlEqualTo("/siteverify")).withRequestBody(containing("response=token-ok")));
		// a conta do doador nasce na confirmacao do pagamento (PedidoApi.confirmarPagamento), nao aqui
		assertThat(jdbc.queryForObject("select count(*) from usuario where email = 'maria@exemplo.com'", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from pedido", Integer.class)).isEqualTo(1);
	}

	@Test
	void deviceHashNaoEGuardado() throws Exception {
		turnstileAprova(true);
		plano("Simples", 3000);
		assertThat(anonimo().post("/api/doacoes", json(doacao())).statusCode()).isEqualTo(201);
		var colunas = jdbc.queryForList("select column_name from information_schema.columns where table_name = 'pedido'", String.class);
		assertThat(colunas).noneMatch(c -> c.contains("device"));
	}

	@Test
	void mantemCsrfMasNaoExigeLogin() throws Exception {
		turnstileAprova(true);
		plano("Simples", 3000);
		var res = new Navegador(porta).post("/api/doacoes", json(doacao()));
		assertThat(res.statusCode()).isEqualTo(403);
		assertThat(corpo(res).get("codigo").asString()).isEqualTo("csrf_invalido");
		assertThat(anonimo().post("/api/doacoes", json(doacao())).statusCode()).isEqualTo(201);
	}

	@Test
	void ordemCamposDepoisCaptchaDepoisDescartavelDepoisRegras() throws Exception {
		plano("Simples", 3000);
		var nav = anonimo();
		turnstileAprova(false);
		// campo invalido e captcha ruim: ganha o campo
		var campo = nav.post("/api/doacoes", json(com(doacao(), "nome", "A")));
		assertThat(campo.statusCode()).isEqualTo(400);
		assertThat(corpo(campo).get("codigo").asString()).isEqualTo("dados_invalidos");
		TURNSTILE.verify(0, postRequestedFor(urlEqualTo("/siteverify")));
		// captcha ruim e e-mail descartavel: ganha o captcha
		var captcha = nav.post("/api/doacoes", json(com(doacao(), "email", "x@mailinator.com")));
		assertThat(captcha.statusCode()).isEqualTo(400);
		assertThat(corpo(captcha).get("codigo").asString()).isEqualTo("captcha_invalido");
		assertThat(corpo(captcha).get("mensagemPastoral").asString()).isEqualTo(CAPTCHA);
		// captcha bom: descartavel
		turnstileAprova(true);
		var descartavel = nav.post("/api/doacoes", json(com(doacao(), "email", "x@mailinator.com")));
		assertThat(descartavel.statusCode()).isEqualTo(400);
		assertThat(corpo(descartavel).get("codigo").asString()).isEqualTo("email_descartavel");
		assertThat(corpo(descartavel).get("mensagemPastoral").asString())
				.isEqualTo("Use um e-mail principal para receber sua oração — e-mails temporários não funcionam aqui.");
		// captcha bom e e-mail bom: regras de valor
		var piso = nav.post("/api/doacoes", json(com(doacao(), "valorCentavos", 50)));
		assertThat(erroDe(piso, "valorCentavos")).isEqualTo("O valor mínimo é R$ 1,00 — qualquer oferta acima ajuda o Clama.");
		assertThat(pedidos.count()).isZero();
	}

	@Test
	void turnstileIndisponivelContaComoCaptchaInvalido() throws Exception {
		TURNSTILE.stubFor(post("/siteverify").willReturn(serviceUnavailable()));
		var res = anonimo().post("/api/doacoes", json(doacao()));
		assertThat(res.statusCode()).isEqualTo(400);
		assertThat(corpo(res).get("codigo").asString()).isEqualTo("captcha_invalido");
	}

	@Test
	void semTokenEErroDeCampo() throws Exception {
		var res = anonimo().post("/api/doacoes", json(com(doacao(), "turnstileToken", null)));
		assertThat(res.statusCode()).isEqualTo(400);
		assertThat(erroDe(res, "turnstileToken")).isEqualTo(CAPTCHA);
		var longo = anonimo().post("/api/doacoes", json(com(doacao(), "turnstileToken", "t".repeat(2049), "deviceHash", "d".repeat(129))));
		assertThat(erroDe(longo, "turnstileToken")).isNotNull();
		assertThat(erroDe(longo, "deviceHash")).isNotNull();
	}

	@Test
	void dezPorHoraPorIp() throws Exception {
		turnstileAprova(false); // 400 barato; o limite vem antes de validar
		var nav = anonimo();
		for (int i = 0; i < 10; i++) assertThat(nav.post("/api/doacoes", json(doacao())).statusCode()).isEqualTo(400);
		var res = nav.post("/api/doacoes", json(doacao()));
		assertThat(res.statusCode()).isEqualTo(429);
		assertThat(corpo(res).get("codigo").asString()).isEqualTo("limite_excedido");
	}
}
