package me.clama.blog.interno;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import me.clama.BancoDeTeste;
import me.clama.infra.web.ErroDeNegocio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/** D6: o espelho resolve pelo banco e so vai ao WordPress quando o slug nao tem espelho. */
@SpringBootTest
@Import(BancoDeTeste.class)
class EspelhoTest {

	static final WireMockServer WP = new WireMockServer(WireMockConfiguration.options().dynamicPort());
	static final String CAMINHO = "/wp-json/wp/v2/posts";

	static {
		WP.start();
	}

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		r.add("clama.blog.wordpress-url", WP::baseUrl);
	}

	@Autowired Espelho espelho;
	@Autowired PostRepository posts;
	@Autowired JdbcTemplate jdbc;
	@Autowired TransactionTemplate transacao;

	@BeforeEach
	@AfterEach
	void limpar() {
		WP.resetAll();
		jdbc.update("delete from blog_post");
	}

	static String post(long id, String slug, boolean protegido) {
		return ClienteWordPressTest.post(id, slug, protegido);
	}

	void wpResponde(String slug, String... itens) {
		WP.stubFor(get(urlPathEqualTo(CAMINHO)).withQueryParam("slug", equalTo(slug)).willReturn(okJson("[" + String.join(",", itens) + "]")));
	}

	void semiar(long id, String slug, boolean publicado) {
		posts.upsert(id, slug, "T", "https://blog.exemplo/" + slug + "/", publicado, Instant.parse("2026-11-01T00:00:00Z"), Instant.now());
	}

	int linhas() {
		return jdbc.queryForObject("select count(*) from blog_post", Integer.class);
	}

	ErroDeNegocio erro(Runnable r) {
		return (ErroDeNegocio) org.junit.jupiter.api.Assertions.assertThrows(ErroDeNegocio.class, r::run);
	}

	@Test
	void espelhoExistenteNaoChamaOWordPress() {
		semiar(7, "fe", true);
		assertThat(espelho.resolver("fe").getWpId()).isEqualTo(7);
		assertThat(WP.getAllServeEvents()).isEmpty();
	}

	@Test
	void ausenteBuscaECria() {
		wpResponde("fe", post(7, "fe", false));
		var p = espelho.resolver("fe");
		assertThat(p.getWpId()).isEqualTo(7);
		assertThat(p.isPublicado()).isTrue();
		assertThat(p.getTitulo()).isEqualTo("Fé – e esperança");
		assertThat(p.getPublicadoEm()).isEqualTo(Instant.parse("2026-11-01T12:30:00Z"));
		assertThat(linhas()).isEqualTo(1);
		espelho.resolver("fe"); // 2a vez: so o banco
		WP.verify(1, getRequestedFor(urlPathEqualTo(CAMINHO)));
	}

	@Test
	void slugQueOWordPressNaoTemE404SemGravar() {
		wpResponde("nada");
		var e = erro(() -> espelho.resolver("nada"));
		assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(e.codigo()).isEqualTo("post_nao_encontrado");
		assertThat(linhas()).isZero();
	}

	@Test
	void naoPublicadoE409ELinhaFicaMesmoComRollbackDeQuemChamou() {
		wpResponde("secreto", post(9, "secreto", true));
		assertThatThrownBy(() -> transacao.executeWithoutResult(s -> espelho.resolver("secreto"))).isInstanceOfSatisfying(ErroDeNegocio.class, e -> {
			assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
			assertThat(e.codigo()).isEqualTo("post_nao_aceita_interacao");
			assertThat(e.mensagemPastoral()).startsWith("Este texto saiu do ar enquanto você escrevia.");
		});
		assertThat(jdbc.queryForObject("select publicado from blog_post where wp_id = 9", Boolean.class)).isFalse();
	}

	@Test
	void espelhoNaoPublicadoNoBancoTambemE409() {
		semiar(9, "fora", false);
		assertThat(erro(() -> espelho.resolver("fora")).status()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(WP.getAllServeEvents()).isEmpty();
	}

	@Test
	void slugRenomeadoAtualizaALinha() {
		semiar(7, "antigo", true);
		wpResponde("novo", post(7, "novo", false));
		assertThat(espelho.resolver("novo").getWpId()).isEqualTo(7);
		assertThat(linhas()).isEqualTo(1);
		assertThat(jdbc.queryForObject("select slug from blog_post where wp_id = 7", String.class)).isEqualTo("novo");
	}

	@Test
	void mesmoSlugEmDoisPostsEscolheOPublicado() {
		semiar(1, "igual", false);
		semiar(2, "igual", true);
		assertThat(espelho.resolver("igual").getWpId()).isEqualTo(2);
		// no WordPress: o protegido vem primeiro na lista, o publicado vence
		wpResponde("outro", post(3, "outro", true), post(4, "outro", false));
		assertThat(espelho.resolver("outro").getWpId()).isEqualTo(4);
	}

	@Test
	void criacaoConcorrenteNaoDuplica() throws Exception {
		wpResponde("fe", post(7, "fe", false));
		int n = 8;
		var largada = new CountDownLatch(1);
		var pool = Executors.newFixedThreadPool(n);
		var futuros = new ArrayList<java.util.concurrent.Future<Long>>();
		for (int i = 0; i < n; i++) futuros.add(pool.submit(() -> {
			largada.await();
			return espelho.resolver("fe").getWpId();
		}));
		largada.countDown();
		for (var f : futuros) assertThat(f.get()).isEqualTo(7);
		pool.shutdown();
		assertThat(linhas()).isEqualTo(1);
	}

	@Test
	void wordPressForaE503SemGravar() {
		WP.stubFor(get(urlPathEqualTo(CAMINHO)).willReturn(serverError()));
		var e = erro(() -> espelho.resolver("fe"));
		assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(e.codigo()).isEqualTo("wordpress_indisponivel");
		assertThat(e.mensagemPastoral()).isEqualTo("Estamos com dificuldade de falar com o blog agora.");
		assertThat(linhas()).isZero();
	}
}
