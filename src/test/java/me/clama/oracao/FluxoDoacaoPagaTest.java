package me.clama.oracao;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import me.clama.BancoDeTeste;
import me.clama.CaixaDeSaidaEmMemoria;
import me.clama.EmailDeTeste;
import me.clama.conta.interno.Papel;
import me.clama.conta.interno.UsuarioRepository;
import me.clama.conta.web.Navegador;
import me.clama.infra.email.EnvioAssincrono;
import me.clama.infra.fila.FilaLigada;
import me.clama.infra.seguranca.LimiteDeTaxa;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Ponta a ponta no backend (spec §3.10): doacao anonima, Pix, webhook assinado do MP, GeracaoSolicitada, gerar-oracao
 * (Claude), enviar-oracao (e-mail) e ENVIADA com a oracao no status publico. Turnstile, MP e Claude num WireMock so.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import({ BancoDeTeste.class, EmailDeTeste.class })
@ActiveProfiles("test")
@FilaLigada
class FluxoDoacaoPagaTest {

	static final WireMockServer FORA = new WireMockServer(WireMockConfiguration.options().dynamicPort());
	static final String SEGREDO = "segredo-do-webhook-de-teste";
	static final long PAG = 1325814423L;

	static {
		FORA.start();
	}

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		r.add("clama.turnstile.url", () -> FORA.baseUrl() + "/siteverify");
		r.add("clama.turnstile.secret", () -> "segredo-de-teste");
		r.add("clama.pagamento.mercadopago-url", FORA::baseUrl);
		r.add("clama.pagamento.mercadopago-token", () -> "test");
		r.add("clama.pagamento.webhook-segredo", () -> SEGREDO);
		r.add("clama.pagamento.webhook-url", () -> "https://api.clama.test/api/webhooks/mercadopago");
		r.add("clama.oracao.anthropic-url", FORA::baseUrl);
		r.add("clama.oracao.anthropic-api-key", () -> "sk-ant-teste");
	}

	@LocalServerPort int porta;
	@Autowired JdbcTemplate jdbc;
	@Autowired CaixaDeSaidaEmMemoria caixa;
	@Autowired EnvioAssincrono envio;
	@Autowired LimiteDeTaxa limite;
	@Autowired UsuarioRepository usuarios;
	@Autowired PasswordEncoder encoder;

	@BeforeEach
	@AfterEach
	void limpar() throws Exception {
		for (int i = 0; i < 500 && envio.pendentes() > 0; i++) Thread.sleep(10);
		FORA.resetAll();
		caixa.limpar();
		limite.limpar();
		jdbc.update(BancoDeTeste.LIMPAR_FILA);
		jdbc.update("delete from pagamento_evento");
		jdbc.update("delete from repasse");
		jdbc.update("delete from cobranca");
		jdbc.update("delete from pedido");
		jdbc.update("delete from instituicao where nome = 'Casa'");
		jdbc.update("delete from usuario where email like '%@exemplo.com'");
		jdbc.update("delete from plano where tipo = 'COMUM'");
	}

	static String assinar(String dataId, String requestId, String ts) throws Exception {
		var mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(SEGREDO.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return HexFormat.of().formatHex(mac.doFinal(("id:" + dataId + ";request-id:" + requestId + ";ts:" + ts + ";")
				.getBytes(StandardCharsets.UTF_8)));
	}

	/** Turnstile, MP (Pix) e Claude respondem; o plano comum de R$ 30 existe. */
	void foraResponde() throws Exception {
		jdbc.update("""
				insert into plano (nome, valor_centavos, complexidade, tipo, ordem) values ('Oração Simples', 3000, 'SIMPLES', 'COMUM', 1)""");
		FORA.stubFor(post("/siteverify").willReturn(okJson("{\"success\": true}")));
		FORA.stubFor(post("/v1/payments").willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
				.withBody(Files.readString(Path.of("src/test/resources/mercadopago/pix_criado.json")))));
		FORA.stubFor(post("/v1/messages").willReturn(okJson("""
				{"id": "msg_1", "type": "message", "role": "assistant", "model": "claude-sonnet-4-6",
				 "content": [{"type": "text", "text": "Senhor, guarda a família da Maria.\\n\\nAmém."}], "stop_reason": "end_turn",
				 "stop_sequence": null, "usage": {"input_tokens": 800, "output_tokens": 120}}""")));
	}

	/** Doacao anonima de maria@exemplo.com, Pix e o webhook assinado do MP aprovando; devolve o id do pedido. */
	UUID doarEPagar() throws Exception {
		// 1. doacao anonima
		var nav = new Navegador(porta);
		nav.get("/api/auth/eu");
		var corpo = new LinkedHashMap<String, Object>();
		corpo.put("nome", "Maria Souza");
		corpo.put("email", "maria@exemplo.com");
		corpo.put("cpfCnpj", "529.982.247-25");
		corpo.put("pedidoOracao", "Pela minha família");
		corpo.put("valorCentavos", 3000);
		corpo.put("instituicao", jdbc.queryForObject("insert into instituicao (nome, ativo) values ('Casa', true) returning id", Long.class));
		corpo.put("consentAceito", true);
		corpo.put("turnstileToken", "token-ok");
		corpo.put("deviceHash", "abcdef1234567890");
		var criado = nav.post("/api/doacoes", Navegador.JSON.writeValueAsString(corpo));
		assertThat(criado.statusCode()).as(criado.body()).isEqualTo(201);
		var id = UUID.fromString(Navegador.json(criado).get("id").asString());

		// 2. Pix
		var pix = nav.post("/api/pedidos/" + id + "/pix", "");
		assertThat(pix.statusCode()).as(pix.body()).isEqualTo(200);

		// 3. o MP aprova e notifica (assinado)
		FORA.stubFor(get("/v1/payments/" + PAG).willReturn(okJson("""
				{"id": %d, "status": "approved", "status_detail": "accredited", "external_reference": "%s",
				 "transaction_amount": 30.00, "currency_id": "BRL"}""".formatted(PAG, id))));
		var ts = "1762434000";
		var webhook = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest
				.newBuilder(java.net.URI.create("http://localhost:" + porta + "/api/webhooks/mercadopago?data.id=" + PAG + "&type=payment"))
				.header("Content-Type", "application/json").header("x-request-id", "req-1")
				.header("x-signature", "ts=" + ts + ",v1=" + assinar(String.valueOf(PAG), "req-1", ts))
				.POST(java.net.http.HttpRequest.BodyPublishers.ofString(
						"{\"id\": 1, \"type\": \"payment\", \"action\": \"payment.updated\", \"data\": {\"id\": \"" + PAG + "\"}}"))
				.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
		assertThat(webhook.statusCode()).as(webhook.body()).isEqualTo(200);
		return id;
	}

	void esperarEnviada(UUID id) {
		await().atMost(Duration.ofSeconds(20)).until(() -> Navegador.json(new Navegador(porta).get("/api/pedidos/" + id))
				.get("status").asString().equals("ENVIADA"));
	}

	CaixaDeSaidaEmMemoria.Enviado oracaoPara(String email) {
		var oracao = caixa.para(email).stream().filter(e -> e.email().assunto().startsWith("Sua oração")).toList();
		assertThat(oracao).hasSize(1);
		return oracao.getFirst();
	}

	@Test
	void oracaoDoAnonimoLevaOLinkDeAtivacao() throws Exception {
		foraResponde();
		var id = doarEPagar();
		esperarEnviada(id);
		var oracao = oracaoPara("maria@exemplo.com").email(); // modelo oracao_ativar_conta
		assertThat(oracao.texto()).contains("Senhor, guarda a família da Maria.").contains("Guarde suas orações: crie sua senha no Clama")
				.contains("http://localhost:5173/definir-senha#token=").contains("O link vale por 72 horas.");
		assertThat(oracao.html()).contains("Guarde suas orações: crie sua senha no Clama").contains("http://localhost:5173/definir-senha#token=");
		assertThat(caixa.para("maria@exemplo.com")).as("nenhum definir senha separado")
				.noneMatch(e -> e.email().assunto().equals("Bem-vinda ao Clama — defina sua senha"));
	}

	@Test
	void oracaoDeQuemTemSenhaUsaOModeloOracao() throws Exception {
		foraResponde();
		jdbc.update("""
				insert into plano (nome, descricao, valor_centavos, complexidade, tipo, ordem)
				values ('Gratuito', 'Sua primeira oração — por nossa conta', 0, 'SIMPLES', 'GRATUITO', 1000)
				on conflict (tipo) where tipo <> 'COMUM' do nothing""");
		var email = "com-senha@exemplo.com";
		Navegador.usuario(usuarios, encoder, email, "senha-forte-123", Papel.CLIENTE);
		var nav = new Navegador(porta);
		assertThat(nav.login(email, "senha-forte-123").statusCode()).isEqualTo(200);
		var corpo = new LinkedHashMap<String, Object>();
		corpo.put("nome", "Maria Souza");
		corpo.put("email", email);
		corpo.put("cpfCnpj", "529.982.247-25");
		corpo.put("pedidoOracao", "Pela minha família");
		corpo.put("consentAceito", true);
		var criado = nav.post("/api/pedidos/gratuito", Navegador.JSON.writeValueAsString(corpo));
		assertThat(criado.statusCode()).as(criado.body()).isEqualTo(201);
		esperarEnviada(UUID.fromString(Navegador.json(criado).get("id").asString()));
		var oracao = oracaoPara(email).email(); // modelo oracao
		assertThat(oracao.texto()).contains("Senhor, guarda a família da Maria.").doesNotContain("definir-senha").doesNotContain("Guarde suas orações");
	}

	@Test
	void doacaoPagaPorPixViraOracaoNoEmailENoStatus() throws Exception {
		foraResponde();
		var id = doarEPagar();

		// 4. a fila gera e envia; o status publico mostra a oracao
		await().atMost(Duration.ofSeconds(20)).until(() -> Navegador.json(new Navegador(porta).get("/api/pedidos/" + id))
				.get("status").asString().equals("ENVIADA"));
		var status = Navegador.json(new Navegador(porta).get("/api/pedidos/" + id));
		assertThat(status.get("oracaoGerada").asString()).isEqualTo("Senhor, guarda a família da Maria.\n\nAmém.");

		var oracao = caixa.para("maria@exemplo.com").stream().filter(e -> e.email().assunto().startsWith("Sua oração")).toList();
		assertThat(oracao).singleElement().satisfies(e -> {
			assertThat(e.chave()).isEqualTo("oracao/" + id);
			assertThat(e.email().assunto()).isEqualTo("Sua oração chegou, Maria");
			assertThat(e.email().texto()).contains("Senhor, guarda a família da Maria.");
		});
		FORA.verify(1, postRequestedFor(urlEqualTo("/v1/messages"))
				.withRequestBody(matchingJsonPath("$.messages[0].content", containing("Pedido: Pela minha família"))));
		assertThat(caixa.para("contato@clama.me")).isEmpty(); // nenhum alerta
	}
}
