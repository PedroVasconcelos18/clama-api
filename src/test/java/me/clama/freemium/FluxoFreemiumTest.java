package me.clama.freemium;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.regex.Pattern;
import me.clama.BancoDeTeste;
import me.clama.CaixaDeSaidaEmMemoria;
import me.clama.EmailDeTeste;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * D13, o fluxo do freemium ate o e-mail: submit, e-mail de confirmacao, confirmacao, "definir senha" e a oracao. Turnstile
 * e Claude num WireMock so, e-mail na caixa em memoria. Antes da confirmacao nao existe e-mail de senha nem conta.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import({ BancoDeTeste.class, EmailDeTeste.class })
@ActiveProfiles("test")
@FilaLigada
class FluxoFreemiumTest {

	static final String TRIAGEM = "responde com UMA palavra"; // so o system da triagem tem
	static final WireMockServer FORA = new WireMockServer(WireMockConfiguration.options().dynamicPort());
	static final Pattern LINK = Pattern.compile("/confirmar#token=([A-Za-z0-9_-]+)");
	static final String EMAIL = "frm-fluxo@exemplo.com";

	static {
		FORA.start();
	}

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		r.add("clama.turnstile.url", () -> FORA.baseUrl() + "/siteverify");
		r.add("clama.turnstile.secret", () -> "segredo-de-teste");
		r.add("clama.oracao.anthropic-url", FORA::baseUrl);
		r.add("clama.oracao.anthropic-api-key", () -> "sk-ant-teste");
	}

	@LocalServerPort int porta;
	@Autowired JdbcTemplate jdbc;
	@Autowired CaixaDeSaidaEmMemoria caixa;
	@Autowired EnvioAssincrono envio;
	@Autowired LimiteDeTaxa limite;

	@BeforeEach
	@AfterEach
	void limpar() throws Exception {
		for (int i = 0; i < 500 && envio.pendentes() > 0; i++) Thread.sleep(10);
		FORA.resetAll();
		caixa.limpar();
		limite.limpar();
		jdbc.update(BancoDeTeste.LIMPAR_FILA);
		jdbc.update("delete from pedido");
		jdbc.update("delete from freemium_bloqueio");
		jdbc.update("delete from usuario where email like 'frm-%'");
		jdbc.update("""
				insert into plano (nome, descricao, valor_centavos, complexidade, tipo, ordem)
				values ('Gratuito', 'Sua primeira oração — por nossa conta', 0, 'SIMPLES', 'GRATUITO', 1000)
				on conflict (tipo) where tipo <> 'COMUM' do nothing""");
	}

	@Test
	void submitConfirmacaoDefinirSenhaEOracaoPorEmail() throws Exception {
		FORA.stubFor(post("/siteverify").willReturn(okJson("{\"success\": true}")));
		FORA.stubFor(post("/v1/messages").willReturn(okJson("""
				{"id": "msg_1", "type": "message", "role": "assistant", "model": "claude-sonnet-4-6",
				 "content": [{"type": "text", "text": "Senhor, guarda a família da Maria.\\n\\nAmém."}], "stop_reason": "end_turn",
				 "stop_sequence": null, "usage": {"input_tokens": 800, "output_tokens": 120}}""")));
		FORA.stubFor(post("/v1/messages").withRequestBody(containing(TRIAGEM)).atPriority(1).willReturn(okJson("""
				{"id": "msg_2", "type": "message", "role": "assistant", "model": "claude-sonnet-4-6",
				 "content": [{"type": "text", "text": "SEM_RISCO"}], "stop_reason": "end_turn",
				 "stop_sequence": null, "usage": {"input_tokens": 300, "output_tokens": 3}}""")));

		// 1. submit
		var nav = new Navegador(porta);
		nav.get("/api/auth/eu");
		var corpo = new LinkedHashMap<String, Object>();
		corpo.put("nome", "Maria Souza");
		corpo.put("email", EMAIL);
		corpo.put("cpfCnpj", "529.982.247-25");
		corpo.put("pedidoOracao", "Pela minha família");
		corpo.put("consentAceito", true);
		corpo.put("turnstileToken", "token-ok");
		var criado = nav.post("/api/freemium/pedidos", Navegador.JSON.writeValueAsString(corpo));
		assertThat(criado.statusCode()).as(criado.body()).isEqualTo(201);
		var id = UUID.fromString(Navegador.json(criado).get("id").asString());

		// 2. o job manda o e-mail de confirmacao, e so ele: nenhum "definir senha" e nenhuma conta antes da confirmacao
		var confirmacao = caixa.aguardarPara(EMAIL, 1);
		assertThat(confirmacao).singleElement().satisfies(e -> assertThat(e.email().assunto()).isEqualTo("Confirme sua oração — clama"));
		var m = LINK.matcher(confirmacao.getFirst().email().texto());
		assertThat(m.find()).isTrue();
		var token = m.group(1);
		Thread.sleep(300); // da tempo a qualquer e-mail indevido aparecer
		assertThat(caixa.para(EMAIL)).as("nenhum definir senha antes da confirmacao")
				.noneMatch(e -> e.email().assunto().contains("defina sua senha"));
		assertThat(jdbc.queryForObject("select count(*) from usuario where email = ?", Integer.class, EMAIL)).isZero();
		assertThat(Navegador.json(new Navegador(porta).get("/api/pedidos/" + id)).get("status").asString()).isEqualTo("AGUARDANDO_CONFIRMACAO_EMAIL");

		// 3. confirmacao
		nav.get("/api/auth/eu");
		var ok = nav.post("/api/freemium/confirmacao", "{\"token\":\"" + token + "\"}");
		assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);

		// 4. "definir senha" e a oracao
		await().atMost(Duration.ofSeconds(20)).until(() -> Navegador.json(new Navegador(porta).get("/api/pedidos/" + id))
				.get("status").asString().equals("ENVIADA"));
		var tudo = caixa.aguardarPara(EMAIL, 3);
		assertThat(tudo).extracting(e -> e.email().assunto())
				.containsExactlyInAnyOrder("Confirme sua oração — clama", "Bem-vinda ao Clama — defina sua senha", "Sua oração chegou, Maria");
		var oracao = tudo.stream().filter(e -> e.email().assunto().startsWith("Sua oração")).findFirst().orElseThrow();
		assertThat(oracao.email().texto()).contains("Senhor, guarda a família da Maria.");
		assertThat(oracao.email().texto()).as("o e-mail da oracao nao leva o link de senha").doesNotContain("definir-senha");
		assertThat(jdbc.queryForObject("select count(*) from usuario where email = ?", Integer.class, EMAIL)).isOne();
		assertThat(jdbc.queryForObject("select count(*) from freemium_bloqueio", Integer.class)).isOne();
		assertThat(caixa.para("contato@clama.me")).isEmpty(); // nenhum alerta
		FORA.verify(1, postRequestedFor(urlEqualTo("/v1/messages")).withRequestBody(notContaining(TRIAGEM)));
		// a triagem do pedido corre em paralelo e grava o risco
		await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.queryForObject("select risco from pedido where id = ?", Boolean.class, id) != null);
		assertThat(jdbc.queryForObject("select risco from pedido where id = ?", Boolean.class, id)).isFalse();
		FORA.verify(1, postRequestedFor(urlEqualTo("/v1/messages")).withRequestBody(containing(TRIAGEM)));
	}
}
