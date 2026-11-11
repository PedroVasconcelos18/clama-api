package me.clama.blog.web.widget;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import me.clama.BancoDeTeste;
import me.clama.blog.interno.Banimentos;
import me.clama.blog.interno.ClienteWordPress;
import me.clama.blog.interno.PostRepository;
import me.clama.conta.interno.Papel;
import me.clama.conta.interno.UsuarioRepository;
import me.clama.conta.web.Navegador;
import me.clama.infra.seguranca.LimiteDeTaxa;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/** Base dos testes HTTP do widget: WordPress simulado, banco limpo antes e depois, usuario logado e o fluxo do token do widget. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(BancoDeTeste.class)
abstract class WidgetTeste {

	static final WireMockServer WP = new WireMockServer(WireMockConfiguration.options().dynamicPort());
	static {
		WP.start();
	}

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		r.add("clama.blog.wordpress-url", WP::baseUrl);
	}

	@LocalServerPort int porta;
	@Autowired JdbcTemplate jdbc;
	@Autowired UsuarioRepository usuarios;
	@Autowired PasswordEncoder encoder;
	@Autowired LimiteDeTaxa limite;
	@Autowired PostRepository posts;
	@MockitoSpyBean ClienteWordPress wordpress;
	@MockitoSpyBean Banimentos banimentos;

	@BeforeEach
	@AfterEach
	void limpar() {
		limite.limpar();
		WP.resetAll();
		org.mockito.Mockito.reset(wordpress, banimentos);
		jdbc.update("delete from blog_comentario");
		jdbc.update("delete from blog_curtida");
		jdbc.update("delete from blog_banimento");
		jdbc.update("delete from blog_post");
		jdbc.update("delete from usuario where email like 'blog-%'");
	}

	record Logado(Navegador nav, Long id, String email) {}

	Logado logado() throws Exception {
		return logado("Fulana de Tal", Papel.CLIENTE);
	}

	Logado logado(String nome, Papel papel) throws Exception {
		var email = "blog-" + UUID.randomUUID() + "@exemplo.com";
		var u = me.clama.conta.interno.Usuario.novo(email, nome, papel, Instant.parse("2026-11-08T12:00:00Z"));
		u.definirSenha(encoder.encode("senha-forte-123"));
		u = usuarios.save(u);
		var nav = new Navegador(porta);
		var res = nav.login(email, "senha-forte-123");
		if (res.statusCode() != 200) throw new IllegalStateException("login falhou: " + res.body());
		return new Logado(nav, u.getId(), email);
	}

	/** O fluxo do widget: GET /api/csrf/ e o token vai em X-CSRFToken. */
	static String csrf(Navegador nav) throws Exception {
		return Navegador.json(nav.get("/api/csrf/")).get("csrf_token").asString();
	}

	static HttpResponse<String> comentar(Navegador nav, String slug, String corpo) throws Exception {
		return nav.post("/api/blog/posts/" + slug + "/comments/", corpo, "X-CSRFToken", csrf(nav));
	}

	static HttpResponse<String> comentarTexto(Navegador nav, String slug, String texto) throws Exception {
		return comentar(nav, slug, Navegador.JSON.writeValueAsString(Map.of("conteudo", texto)));
	}

	static HttpResponse<String> curtir(Navegador nav, String slug) throws Exception {
		return nav.post("/api/blog/posts/" + slug + "/like/", "", "X-CSRFToken", csrf(nav));
	}

	static JsonNode json(HttpResponse<String> res) {
		return Navegador.json(res);
	}

	void semear(long id, String slug, boolean publicado) {
		posts.upsert(id, slug, "Titulo", "https://blog.exemplo/" + slug + "/", publicado, Instant.parse("2026-11-01T00:00:00Z"), Instant.now());
	}

	int quantos(String tabela) {
		return jdbc.queryForObject("select count(*) from " + tabela, Integer.class);
	}

	void banir(Long usuarioId) {
		jdbc.update("insert into blog_banimento (usuario_id, motivo, banido_em) values (?, 'teste', now())", usuarioId);
	}

	void wpTem(String slug, String... itens) {
		WP.stubFor(get(urlPathEqualTo("/wp-json/wp/v2/posts")).withQueryParam("slug", equalTo(slug))
				.willReturn(okJson("[" + String.join(",", itens) + "]")));
	}

	static String wpPost(long id, String slug, boolean protegido) {
		return "{\"id\":" + id + ",\"slug\":\"" + slug + "\",\"title\":{\"rendered\":\"Titulo\"},\"link\":\"https://blog.exemplo/" + slug
				+ "/\",\"date_gmt\":\"2026-11-01T12:30:00\",\"content\":{\"protected\":" + protegido + "}}";
	}
}
