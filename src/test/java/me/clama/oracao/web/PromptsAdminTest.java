package me.clama.oracao.web;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import me.clama.BancoDeTeste;
import me.clama.EmailDeTeste;
import me.clama.conta.interno.Papel;
import me.clama.conta.interno.UsuarioRepository;
import me.clama.conta.web.Navegador;
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
import tools.jackson.databind.JsonNode;

/** Admin de prompts (D15): versoes imutaveis, uma ativa, voltar ao padrao, previa contra a Claude em WireMock, auditoria. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import({ BancoDeTeste.class, EmailDeTeste.class })
@ActiveProfiles("test")
class PromptsAdminTest {

	static final WireMockServer CLAUDE = new WireMockServer(WireMockConfiguration.options().dynamicPort());
	static final String RAIZ = "/api/admin/prompts";
	static final String OK = """
			{"id": "msg_1", "type": "message", "role": "assistant", "model": "claude-sonnet-4-6",
			 "content": [{"type": "text", "text": "Senhor, acolhe a Joana."}], "stop_reason": "end_turn",
			 "stop_sequence": null, "usage": {"input_tokens": 10, "output_tokens": 5}}""";

	static {
		CLAUDE.start();
	}

	@DynamicPropertySource
	static void claude(DynamicPropertyRegistry r) {
		r.add("clama.oracao.anthropic-url", CLAUDE::baseUrl);
		r.add("clama.oracao.anthropic-api-key", () -> "sk-ant-teste");
	}

	@LocalServerPort int porta;
	@Autowired JdbcTemplate jdbc;
	@Autowired UsuarioRepository usuarios;
	@Autowired PasswordEncoder encoder;

	@BeforeEach
	@AfterEach
	void limpar() {
		CLAUDE.resetAll();
		jdbc.update("delete from auditoria where entidade = 'prompt_versao'");
		jdbc.update("delete from prompt_versao");
		jdbc.update("delete from usuario where email like 'adm-%@exemplo.com'");
	}

	@Test
	void anonimoRecebe401EClienteRecebe403() throws Exception {
		assertThat(new Navegador(porta).get(RAIZ).statusCode()).isEqualTo(401);
		assertThat(entrar(Papel.CLIENTE).get(RAIZ).statusCode()).isEqualTo(403);
	}

	@Test
	void semVersaoListaSoOPadraoVigente() throws Exception {
		var lista = lista(entrar(Papel.ADMIN));
		assertThat(lista).hasSize(1);
		var padrao = lista.get(0);
		assertThat(padrao.get("padrao").asBoolean()).isTrue();
		assertThat(padrao.get("ativo").asBoolean()).isTrue();
		assertThat(padrao.get("id").isNull()).isTrue();
		assertThat(padrao.at("/conteudo/sistema").asString()).startsWith("# Identidade");
		assertThat(padrao.at("/conteudo/gratuito").asString()).startsWith("Gere uma oração pastoral");
	}

	/** Cada gravacao e uma versao nova, inativa; a mesma gravacao duas vezes sao duas versoes. */
	@Test
	void cadaGravacaoCriaVersaoNovaInativaEAuditada() throws Exception {
		var adm = entrar(Papel.ADMIN);
		var a = criar(adm, prompt("Sistema."));
		var b = criar(adm, prompt("Sistema."));
		assertThat(b).isGreaterThan(a);

		var lista = lista(adm);
		assertThat(lista).extracting(n -> n.get("padrao").asBoolean()).containsExactly(true, false, false);
		assertThat(lista).extracting(n -> n.get("ativo").asBoolean()).containsExactly(true, false, false);
		assertThat(lista.get(1).get("id").asLong()).isEqualTo(b);
		assertThat(lista.get(1).at("/conteudo/sistema").asString()).isEqualTo("Sistema.");
		assertThat(lista.get(1).get("criadoPor").asLong()).isEqualTo(autor(adm));
		assertThat(lista.get(1).get("criadoEm").isString()).isTrue();
		assertThat(jdbc.queryForObject("select count(*) from auditoria where entidade = 'prompt_versao' and acao = 'ADICAO' and autor_id = ?",
				Integer.class, autor(adm))).isEqualTo(2);
	}

	@Test
	void textoInvalidoRecusadoSemGravar() throws Exception {
		var adm = entrar(Papel.ADMIN);
		for (var corpo : List.of(prompt(" "), prompt("x".repeat(50_001)),
				"{\"sistema\": \"S\", \"simples\": \"s\", \"comVersiculo\": \"v\", \"comProfeciaEVersiculos\": \"p\"}",
				prompt("S").replace("\"gratuito\": \"g\"", "\"gratuito\": \"" + "g".repeat(5_001) + "\""))) {
			assertThat(adm.post(RAIZ, corpo).statusCode()).isEqualTo(400);
		}
		assertThat(jdbc.queryForObject("select count(*) from prompt_versao", Integer.class)).isZero();
	}

	@Test
	void ativarTrocaAVigenteEVoltarAoPadraoDesativaTodas() throws Exception {
		var adm = entrar(Papel.ADMIN);
		var a = criar(adm, prompt("A."));
		var b = criar(adm, prompt("B."));

		var res = adm.post(RAIZ + "/" + a + "/ativacao", "");
		assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
		assertThat(Navegador.json(res).get("ativo").asBoolean()).isTrue();
		assertThat(ativos()).containsExactly(a);
		assertThat(lista(adm).get(0).get("ativo").asBoolean()).isFalse();

		assertThat(adm.post(RAIZ + "/" + b + "/ativacao", "").statusCode()).isEqualTo(200);
		assertThat(ativos()).containsExactly(b);
		assertThat(adm.post(RAIZ + "/" + b + "/ativacao", "").statusCode()).isEqualTo(200); // repetir: no-op

		assertThat(adm.delete(RAIZ + "/ativacao").statusCode()).isEqualTo(204);
		assertThat(ativos()).isEmpty();
		assertThat(lista(adm).get(0).get("ativo").asBoolean()).isTrue();
		assertThat(adm.delete(RAIZ + "/ativacao").statusCode()).isEqualTo(204); // ja no padrao: no-op

		// a ligada, a desligada, b ligada, b desligada
		assertThat(jdbc.queryForList("select entidade_id || ':' || (diff -> 'ativo' ->> 'depois') from auditoria "
				+ "where entidade = 'prompt_versao' and acao = 'MUDANCA' order by id", String.class))
				.containsExactly(a + ":true", a + ":false", b + ":true", b + ":false");
	}

	@Test
	void ativarInexistenteE404() throws Exception {
		assertThat(entrar(Papel.ADMIN).post(RAIZ + "/999999/ativacao", "").statusCode()).isEqualTo(404);
	}

	/** A previa monta o prompt da versao escolhida com o pedido de exemplo e chama a Claude uma vez. */
	@Test
	void previaUsaAVersaoEscolhida() throws Exception {
		var adm = entrar(Papel.ADMIN);
		var v = criar(adm, prompt("Sistema da versão."));
		CLAUDE.stubFor(post("/v1/messages").willReturn(okJson(OK)));

		var res = adm.post(RAIZ + "/previa", """
				{"versaoId": %d, "nome": "Joana", "sexo": "feminino", "idade": 40, "pedidoOracao": "Pela saúde",
				 "complexidade": "COM_VERSICULO", "tipo": "COMUM"}""".formatted(v));

		assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
		assertThat(Navegador.json(res).get("oracao").asString()).isEqualTo("Senhor, acolhe a Joana.");
		assertThat(Navegador.json(res).get("versaoId").asLong()).isEqualTo(v);
		CLAUDE.verify(1, postRequestedFor(urlEqualTo("/v1/messages")).withRequestBody(equalToJson("""
				{"system": [{"type": "text", "text": "Sistema da versão.", "cache_control": {"type": "ephemeral"}}],
				 "messages": [{"role": "user", "content": "v\\n\\nNome: Joana\\nSexo: feminino\\nIdade: 40\\n\\nPedido: Pela saúde"}]}""",
				true, true)));
	}

	@Test
	void previaSemVersaoUsaOPadraoComExemploPadrao() throws Exception {
		CLAUDE.stubFor(post("/v1/messages").willReturn(okJson(OK)));

		var res = entrar(Papel.ADMIN).post(RAIZ + "/previa", "{}");

		assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
		assertThat(Navegador.json(res).get("versaoId").isNull()).isTrue();
		CLAUDE.verify(postRequestedFor(urlEqualTo("/v1/messages"))
				.withRequestBody(matchingJsonPath("$.system[0].text", matching("# Identidade(?s).*")))
				.withRequestBody(matchingJsonPath("$.messages[0].content", matching("Complexidade: SIMPLES(?s).*Nome: Maria(?s).*"))));
	}

	@Test
	void previaDeVersaoInexistenteE404() throws Exception {
		assertThat(entrar(Papel.ADMIN).post(RAIZ + "/previa", "{\"versaoId\": 999999}").statusCode()).isEqualTo(404);
		CLAUDE.verify(0, postRequestedFor(anyUrl()));
	}

	@Test
	void previaComErroDaClaude() throws Exception {
		var adm = entrar(Papel.ADMIN);
		erro(400, """
				{"type": "error", "error": {"type": "invalid_request_error", "message": "Your credit balance is too low to access the Anthropic API."}}""");
		confere(adm.post(RAIZ + "/previa", "{}"), 503, "anthropic_sem_creditos");

		erro(529, """
				{"type": "error", "error": {"type": "overloaded_error", "message": "Overloaded"}}""");
		confere(adm.post(RAIZ + "/previa", "{}"), 503, "anthropic_indisponivel");
		CLAUDE.verify(2, postRequestedFor(urlEqualTo("/v1/messages"))); // uma chamada por previa, sem retentativa

		CLAUDE.stubFor(post("/v1/messages").willReturn(okJson("""
				{"id": "msg_1", "type": "message", "role": "assistant", "model": "claude-sonnet-4-6",
				 "content": [{"type": "tool_use", "id": "t1", "name": "x", "input": {}}], "stop_reason": "tool_use",
				 "stop_sequence": null, "usage": {"input_tokens": 1, "output_tokens": 1}}""")));
		var res = adm.post(RAIZ + "/previa", "{}");
		confere(res, 502, "anthropic_resposta_invalida");
		assertThat(res.body()).doesNotContain("tool_use");
	}

	@Test
	void exemploInvalidoE400() throws Exception {
		var adm = entrar(Papel.ADMIN);
		for (var corpo : List.of("{\"nome\": \"" + "n".repeat(121) + "\"}", "{\"pedidoOracao\": \"" + "p".repeat(2001) + "\"}",
				"{\"sexo\": \"outro\"}", "{\"idade\": 0}", "{\"complexidade\": \"ENORME\"}")) {
			assertThat(adm.post(RAIZ + "/previa", corpo).statusCode()).as(corpo).isEqualTo(400);
		}
		CLAUDE.verify(0, postRequestedFor(anyUrl()));
	}

	static void erro(int status, String corpo) {
		CLAUDE.stubFor(post("/v1/messages").willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(corpo)));
	}

	static void confere(HttpResponse<String> res, int status, String codigo) {
		assertThat(res.statusCode()).as(res.body()).isEqualTo(status);
		assertThat(Navegador.json(res).get("codigo").asString()).isEqualTo(codigo);
	}

	static String prompt(String sistema) {
		return """
				{"sistema": "%s", "simples": "s", "comVersiculo": "v", "comProfeciaEVersiculos": "p", "gratuito": "g"}"""
				.formatted(sistema);
	}

	long criar(Navegador adm, String corpo) throws Exception {
		var res = adm.post(RAIZ, corpo);
		assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
		var n = Navegador.json(res);
		assertThat(n.get("ativo").asBoolean()).isFalse();
		assertThat(n.get("padrao").asBoolean()).isFalse();
		return n.get("id").asLong();
	}

	List<JsonNode> lista(Navegador nav) throws Exception {
		var res = nav.get(RAIZ);
		assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
		var l = new ArrayList<JsonNode>();
		Navegador.json(res).forEach(l::add);
		return l;
	}

	List<Long> ativos() {
		return jdbc.queryForList("select id from prompt_versao where ativo", Long.class);
	}

	final java.util.Map<Navegador, Long> autores = new java.util.IdentityHashMap<>();

	long autor(Navegador nav) {
		return autores.get(nav);
	}

	Navegador entrar(Papel papel) throws Exception {
		var email = "adm-" + UUID.randomUUID() + "@exemplo.com";
		var u = Navegador.usuario(usuarios, encoder, email, "senha-forte-123", papel);
		var nav = new Navegador(porta);
		assertThat(nav.login(email, "senha-forte-123").statusCode()).isEqualTo(200);
		autores.put(nav, u.getId());
		return nav;
	}
}
