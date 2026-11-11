package me.clama.blog.web.widget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

import com.github.tomakehurst.wiremock.client.WireMock;
import java.util.ArrayList;
import java.util.List;
import me.clama.conta.interno.Papel;
import me.clama.conta.web.Navegador;
import org.junit.jupiter.api.Test;

/** D14: o contrato que o widget do WordPress (imutavel) le. Cada linha da tabela de erros do mapa §4.3 e provada aqui. */
class ContratoDoWidgetTest extends WidgetTeste {

	static final String CURTO = "Esse comentário precisa de um pouquinho mais de texto pra ser publicado.";
	static final String LONGO = "Esse comentário passou de 2000 caracteres. Encurte um pouco e tente de novo.";
	static final String GENERICA = "Algo não saiu como esperado. Tente novamente em instantes.";
	static final String SAIU_DO_AR = "Este texto saiu do ar enquanto você escrevia. Copiamos o que você digitou — guarde, e tente de novo quando ele voltar.";
	static final String SUSPENSA = "Sua conta foi suspensa do sistema de comentários. Entre em contato pelo nosso e-mail de suporte se quiser entender o motivo.";

	private static List<String> campos(tools.jackson.databind.JsonNode n) {
		var l = new ArrayList<String>();
		n.propertyNames().forEach(l::add);
		return l;
	}

	/** Corpo plano {code, pastoral_message}, nada mais. */
	private static void erro(java.net.http.HttpResponse<String> res, int status, String code, String pastoral) {
		assertThat(res.statusCode()).isEqualTo(status);
		var j = json(res);
		assertThat(campos(j)).containsExactlyInAnyOrder("code", "pastoral_message");
		assertThat(j.get("code").asString()).isEqualTo(code);
		assertThat(j.get("pastoral_message").asString()).isEqualTo(pastoral);
	}

	@Test
	void csrfDevolveOTokenIgualAoCookieSemSessao() throws Exception {
		var nav = new Navegador(porta);
		var res = nav.get("/api/csrf/");
		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(campos(json(res))).containsExactly("csrf_token");
		assertThat(json(res).get("csrf_token").asString()).isNotBlank().isEqualTo(nav.cookie("XSRF-TOKEN"));
		assertThat(nav.cookie("SESSION")).isNull();
		assertThat(res.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
		// com o cookie ja existente (login), devolve o mesmo valor
		var l = logado();
		assertThat(csrf(l.nav())).isEqualTo(l.nav().cookie("XSRF-TOKEN"));
	}

	@Test
	void semBarraFinalE404() throws Exception {
		var anonimo = new Navegador(porta);
		var l = logado();
		for (var nav : List.of(anonimo, l.nav())) {
			for (var caminho : List.of("/api/csrf", "/api/blog/posts/fe/comments", "/api/blog/posts/fe/like")) {
				assertThat(nav.get(caminho).statusCode()).as(caminho).isEqualTo(404);
			}
		}
		// e com barra a rota existe
		assertThat(anonimo.get("/api/blog/posts/fe/comments/").statusCode()).isEqualTo(200);
	}

	@Test
	void listaSemEspelhoVaziaSemChamarOWordPressECacheavelSemCookie() throws Exception {
		var nav = new Navegador(porta);
		var res = nav.get("/api/blog/posts/inexistente/comments/");
		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(json(res).toString()).isEqualTo("{\"count\":0,\"results\":[]}");
		assertThat(res.headers().firstValue("Cache-Control")).hasValue("public, max-age=10");
		assertThat(res.headers().allValues("Set-Cookie")).isEmpty();
		assertThat(WP.getAllServeEvents()).isEmpty();
	}

	@Test
	void listaDeUmPostNaoPublicadoVazia() throws Exception {
		semear(5, "fora", false);
		var l = logado();
		jdbc.update("insert into blog_comentario (post_id, usuario_id, conteudo, suspeito, criado_em) values (5, ?, 'oi tudo bem', false, now())", l.id());
		assertThat(json(new Navegador(porta).get("/api/blog/posts/fora/comments/")).get("count").asLong()).isZero();
	}

	@Test
	void itemEListaTemOsCamposExatos() throws Exception {
		semear(7, "fe", true);
		var l = logado();
		var criado = comentarTexto(l.nav(), "fe", "Belo texto");
		assertThat(criado.statusCode()).isEqualTo(201);
		assertThat(campos(json(criado))).containsExactlyInAnyOrder("id", "customer_nome", "conteudo", "created_at");
		assertThat(json(criado).get("customer_nome").asString()).isEqualTo("Fulana T.");
		assertThat(json(criado).get("conteudo").asString()).isEqualTo("Belo texto");
		assertThat(java.time.Instant.parse(json(criado).get("created_at").asString())).isNotNull();

		var lista = new Navegador(porta).get("/api/blog/posts/fe/comments/"); // anonimo le
		assertThat(campos(json(lista))).containsExactlyInAnyOrder("count", "results");
		assertThat(json(lista).get("count").asLong()).isEqualTo(1);
		var item = json(lista).get("results").get(0);
		assertThat(campos(item)).containsExactlyInAnyOrder("id", "customer_nome", "conteudo", "created_at");
		assertThat(item.get("id")).isEqualTo(json(criado).get("id"));
		assertThat(lista.body()).doesNotContain(l.email());
		// so o "id" pode ser igual ao id do usuario (as sequencias coincidem as vezes): nenhum outro valor do item o carrega
		item.properties().stream().filter(e -> !e.getKey().equals("id"))
				.forEach(e -> assertThat(e.getValue().asString()).isNotEqualTo(String.valueOf(l.id())));
	}

	@Test
	void listaTraOs20MaisNovosEOTotal() throws Exception {
		semear(7, "fe", true);
		var l = logado();
		for (int i = 1; i <= 22; i++) {
			jdbc.update("insert into blog_comentario (post_id, usuario_id, conteudo, suspeito, criado_em) values (7, ?, ?, false, now() + make_interval(secs => ?))",
					l.id(), "comentario " + i, i);
		}
		var j = json(new Navegador(porta).get("/api/blog/posts/fe/comments/"));
		assertThat(j.get("count").asLong()).isEqualTo(22);
		assertThat(j.get("results")).hasSize(20);
		assertThat(j.get("results").get(0).get("conteudo").asString()).isEqualTo("comentario 22");
	}

	@Test
	void curtidaComCorpoVazioDevolveLikedELikeCount() throws Exception {
		semear(7, "fe", true);
		var l = logado();
		var res = curtir(l.nav(), "fe");
		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(campos(json(res))).containsExactlyInAnyOrder("liked", "like_count");
		assertThat(json(res).get("liked").asBoolean()).isTrue();
		assertThat(json(res).get("like_count").asLong()).isEqualTo(1);
		assertThat(json(curtir(l.nav(), "fe")).get("liked").asBoolean()).isFalse();
	}

	@Test
	void postSemXCsrfTokenE403MesmoComOHeaderDoSpa() throws Exception {
		semear(7, "fe", true);
		var l = logado(); // o Navegador manda X-XSRF-TOKEN sozinho; na cadeia do widget so o X-CSRFToken vale
		var res = l.nav().post("/api/blog/posts/fe/like/", "");
		assertThat(res.statusCode()).isEqualTo(403);
		assertThat(l.nav().post("/api/blog/posts/fe/comments/", "{\"conteudo\":\"Belo texto\"}").statusCode()).isEqualTo(403);
		assertThat(l.nav().post("/api/blog/posts/fe/like/", "", "X-CSRFToken", "errado").statusCode()).isEqualTo(403);
		assertThat(quantos("blog_curtida")).isZero();
	}

	@Test
	void anonimoCom_TokenValidoE401() throws Exception {
		semear(7, "fe", true);
		var nav = new Navegador(porta);
		var res = nav.post("/api/blog/posts/fe/like/", "", "X-CSRFToken", csrf(nav));
		assertThat(res.statusCode()).isEqualTo(401);
		assertThat(nav.post("/api/blog/posts/fe/comments/", "{\"conteudo\":\"Belo texto\"}", "X-CSRFToken", csrf(nav)).statusCode()).isEqualTo(401);
	}

	// --- tabela de erros ---

	@Test
	void comentarioInvalidoSaiComoComentarioInvalido() throws Exception {
		semear(7, "fe", true);
		var l = logado();
		for (var corpo : List.of("{\"conteudo\":\"  ab  \"}", "{\"conteudo\":\"\"}", "{\"conteudo\":\"   \"}", "{\"conteudo\":null}",
				"{}", "", "[]", "{\"conteudo\":12345}")) {
			limite.limpar(); // cada tentativa gasta a janela de 5 por minuto
			erro(comentar(l.nav(), "fe", corpo), 400, "comentario_invalido", CURTO);
		}
		assertThat(quantos("blog_comentario")).isZero();
	}

	@Test
	void comentarioLongo() throws Exception {
		semear(7, "fe", true);
		var l = logado();
		erro(comentarTexto(l.nav(), "fe", "a".repeat(2001)), 400, "comentario_longo", LONGO);
	}

	@Test
	void corpoQueNaoEJson() throws Exception {
		semear(7, "fe", true);
		erro(comentar(logado().nav(), "fe", "isto nao e json"), 400, "corpo_invalido", GENERICA);
	}

	@Test
	void postQueOWordPressNaoTem404() throws Exception {
		wpTem("sumiu");
		var l = logado();
		erro(comentarTexto(l.nav(), "sumiu", "Belo texto"), 404, "post_nao_encontrado", "Não encontramos este texto. Ele pode ter saído do ar.");
		erro(curtir(l.nav(), "sumiu"), 404, "post_nao_encontrado", "Não encontramos este texto. Ele pode ter saído do ar.");
	}

	@Test
	void postNaoPublicado409() throws Exception {
		semear(7, "fora", false);
		var l = logado();
		erro(comentarTexto(l.nav(), "fora", "Belo texto"), 409, "post_nao_aceita_interacao", SAIU_DO_AR);
		erro(curtir(l.nav(), "fora"), 409, "post_nao_aceita_interacao", SAIU_DO_AR);
	}

	@Test
	void contaBanida409NosDoisEndpoints() throws Exception {
		semear(7, "fe", true);
		var l = logado();
		banir(l.id());
		erro(comentarTexto(l.nav(), "fe", "Belo texto"), 409, "comentarios_suspensos", SUSPENSA);
		erro(curtir(l.nav(), "fe"), 409, "comentarios_suspensos", SUSPENSA);
	}

	@Test
	void sextoComentarioNoMinuto429() throws Exception {
		semear(7, "fe", true);
		var l = logado();
		for (int i = 0; i < 5; i++) assertThat(comentarTexto(l.nav(), "fe", "Comentario " + i).statusCode()).isEqualTo(201);
		erro(comentarTexto(l.nav(), "fe", "Comentario 6"), 429, "limite_excedido", "Calma! Aguarde um momento antes de comentar de novo.");
	}

	@Test
	void trigesimaPrimeiraCurtida429() throws Exception {
		semear(7, "fe", true);
		var l = logado();
		for (int i = 0; i < 30; i++) assertThat(curtir(l.nav(), "fe").statusCode()).isEqualTo(200);
		erro(curtir(l.nav(), "fe"), 429, "limite_excedido", "Calma! Aguarde um momento antes de reagir de novo.");
	}

	@Test
	void wordPressForaDeAr503SemGravar() throws Exception {
		WP.stubFor(WireMock.get(WireMock.urlPathEqualTo("/wp-json/wp/v2/posts")).willReturn(WireMock.serverError()));
		var l = logado();
		var msg = "Estamos com dificuldade de falar com o blog agora.";
		erro(comentarTexto(l.nav(), "novo", "Belo texto"), 503, "wordpress_indisponivel", msg);
		erro(curtir(l.nav(), "novo"), 503, "wordpress_indisponivel", msg);
		assertThat(quantos("blog_post") + quantos("blog_comentario") + quantos("blog_curtida")).isZero();
	}

	@Test
	void erroInesperado500() throws Exception {
		semear(7, "fe", true);
		var l = logado();
		doThrow(new RuntimeException("falha com dado pessoal")).when(banimentos).estaBanido(anyLong());
		var res = comentarTexto(l.nav(), "fe", "Belo texto");
		erro(res, 500, "erro_interno", GENERICA);
		assertThat(res.body()).doesNotContain("dado pessoal");
	}

	@Test
	void acceptNaoAtendidoE406PlanoENaoUm500() throws Exception {
		var res = new Navegador(porta).get("/api/blog/posts/fe/comments/", "Accept", "text/html");
		erro(res, 406, "requisicao_invalida", GENERICA);
	}

	// --- o advice nao vaza ---

	@Test
	void oRestoDaApiSegueEmProblemDetail() throws Exception {
		var l = logado();
		// X-XSRF-TOKEN do SPA continua valendo fora do widget: o POST passa do CSRF e cai na validacao
		var invalido = l.nav().post("/api/pedidos", "{}");
		assertThat(invalido.statusCode()).isEqualTo(400);
		assertThat(invalido.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
		assertThat(campos(json(invalido))).contains("codigo", "mensagemPastoral").doesNotContain("code", "pastoral_message");
		// 404 de uma rota inexistente de quem esta logado tambem
		var nada = l.nav().get("/api/conta/nao-existe");
		assertThat(nada.statusCode()).isEqualTo(404);
		assertThat(campos(json(nada))).contains("codigo").doesNotContain("code", "pastoral_message");
		// e o 401 do filtro do widget segue ProblemDetail (o widget nao le esse corpo)
		var nav = new Navegador(porta);
		var anonimo = nav.post("/api/blog/posts/fe/like/", "", "X-CSRFToken", csrf(nav));
		assertThat(campos(json(anonimo))).contains("codigo").doesNotContain("pastoral_message");
	}

	@Test
	void adminTambemComentaEPassaPeloBanimento() throws Exception {
		semear(7, "fe", true);
		var admin = logado("Ana Admin", Papel.ADMIN);
		assertThat(comentarTexto(admin.nav(), "fe", "Belo texto").statusCode()).isEqualTo(201);
		banir(admin.id()); // D9: o ban vale para qualquer papel
		assertThat(comentarTexto(admin.nav(), "fe", "Belo texto").statusCode()).isEqualTo(409);
	}
}
