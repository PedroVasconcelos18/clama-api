package me.clama.blog.interno;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import me.clama.BancoDeTeste;
import me.clama.infra.fila.TarefaRecorrente;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** {@code reconciliar-espelho}: cria, atualiza so o que mudou, despublica o que sumiu, nunca apaga e aborta sem gravar. */
@SpringBootTest
@Import(BancoDeTeste.class)
class ReconciliacaoDoEspelhoTest {

	static final WireMockServer WP = new WireMockServer(WireMockConfiguration.options().dynamicPort());
	static final String CAMINHO = "/wp-json/wp/v2/posts";

	static {
		WP.start();
	}

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		r.add("clama.blog.wordpress-url", WP::baseUrl);
	}

	@Autowired ReconciliacaoDoEspelho tarefa;
	@Autowired PostRepository posts;
	@Autowired JdbcTemplate jdbc;

	@BeforeEach
	@AfterEach
	void limpar() {
		WP.resetAll();
		jdbc.update("delete from blog_post");
	}

	static String post(long id, String slug, boolean protegido) {
		return ClienteWordPressTest.post(id, slug, protegido);
	}

	void pagina(int n, int total, String... itens) {
		WP.stubFor(get(urlPathEqualTo(CAMINHO)).withQueryParam("page", equalTo("" + n))
				.willReturn(okJson("[" + String.join(",", itens) + "]").withHeader("X-WP-TotalPages", "" + total)));
	}

	void semiar(long id, String slug, boolean publicado) {
		posts.upsert(id, slug, "Fé – e esperança", "https://blog.exemplo/" + slug + "/", publicado, Instant.parse("2026-11-01T12:30:00Z"),
				Instant.parse("2026-01-01T00:00:00Z"));
	}

	int linhas() {
		return jdbc.queryForObject("select count(*) from blog_post", Integer.class);
	}

	Instant atualizadoEm(long id) {
		return jdbc.queryForObject("select atualizado_em from blog_post where wp_id = ?", java.sql.Timestamp.class, id).toInstant();
	}

	@Test
	void nomeECron() {
		assertThat(tarefa.getName()).isEqualTo("reconciliar-espelho");
		var base = Instant.parse("2026-11-05T15:03:00Z");
		assertThat(tarefa.agenda().getInitialExecutionTime(base)).isEqualTo(Instant.parse("2026-11-05T15:15:00Z"));
	}

	@Test
	void criaEAtualizaSoOQueMudou() {
		semiar(1, "igual", true);
		semiar(2, "velho", true);
		var antes1 = atualizadoEm(1);
		pagina(1, 2, post(1, "igual", false), post(2, "novo", false));
		pagina(2, 2, post(3, "criado", true));
		assertThat(tarefa.executar(false)).isEqualTo("2 post(s) criados ou atualizados, 0 deixaram de estar publicados");
		assertThat(linhas()).isEqualTo(3);
		assertThat(atualizadoEm(1)).isEqualTo(antes1); // igual: nao grava
		assertThat(jdbc.queryForObject("select slug from blog_post where wp_id = 2", String.class)).isEqualTo("novo");
		assertThat(atualizadoEm(2)).isAfter(antes1);
		assertThat(jdbc.queryForObject("select publicado from blog_post where wp_id = 3", Boolean.class)).isFalse();
	}

	@Test
	void sumiuDaListaViraNaoPublicadoENuncaApaga() {
		semiar(1, "fica", true);
		semiar(2, "sumiu", true);
		semiar(3, "ja-fora", false);
		var antes3 = atualizadoEm(3);
		pagina(1, 1, post(1, "fica", false));
		tarefa.executar(false);
		assertThat(linhas()).isEqualTo(3);
		assertThat(jdbc.queryForObject("select publicado from blog_post where wp_id = 2", Boolean.class)).isFalse();
		assertThat(jdbc.queryForObject("select publicado from blog_post where wp_id = 1", Boolean.class)).isTrue();
		assertThat(atualizadoEm(3)).isEqualTo(antes3);
	}

	@Test
	void linhaAtualizadaDepoisDoInicioDaLeituraNaoEDespublicada() {
		// um POST espelhou o post entre a leitura da lista e o findAll: a lista velha nao o conhece, mas ele e novo
		var inicio = Instant.parse("2026-11-12T10:00:00Z");
		posts.upsert(1, "antigo", "T", "https://blog.exemplo/antigo/", true, inicio, inicio.minusSeconds(60));
		posts.upsert(2, "novo", "T", "https://blog.exemplo/novo/", true, inicio, inicio.plusSeconds(1));
		assertThat(posts.despublicar(List.of(1L, 2L), inicio.plusSeconds(5), inicio)).isEqualTo(1);
		assertThat(jdbc.queryForList("select wp_id from blog_post where publicado", Long.class)).containsExactly(2L);
	}

	void abortaSemGravar() {
		assertThat(linhas()).isEqualTo(2);
		assertThat(jdbc.queryForList("select wp_id from blog_post where publicado order by wp_id", Long.class)).containsExactly(1L, 2L);
		assertThat(jdbc.queryForObject("select count(*) from blog_post where slug = 'novo'", Integer.class)).isZero();
	}

	@Test
	void falhaNaSegundaPaginaAbortaSemAplicarNada() {
		semiar(1, "a", true);
		semiar(2, "b", true);
		pagina(1, 2, post(1, "a", false), post(9, "novo", false));
		WP.stubFor(get(urlPathEqualTo(CAMINHO)).withQueryParam("page", equalTo("2")).willReturn(serverError()));
		assertThatThrownBy(() -> tarefa.executar(false)).isInstanceOf(TarefaRecorrente.FalhaParcial.class)
				.hasMessage("abortada, nada foi gravado");
		abortaSemGravar();
	}

	@Test
	void maisDeCinquentaPaginasAbortaSemAplicarNada() {
		semiar(1, "a", true);
		semiar(2, "b", true);
		pagina(1, 51, post(1, "a", false), post(9, "novo", false));
		assertThatThrownBy(() -> tarefa.executar(false)).isInstanceOf(TarefaRecorrente.FalhaParcial.class)
				.hasMessage("abortada, nada foi gravado");
		abortaSemGravar();
		WP.verify(1, getRequestedFor(urlPathEqualTo(CAMINHO))); // nem tenta as outras
	}

	@Test
	void cinquentaPaginasPassam() {
		pagina(1, 50, post(1, "a", false));
		IntStream.rangeClosed(2, 50).forEach(n -> pagina(n, 50, post(100 + n, "p" + n, false)));
		tarefa.executar(false);
		assertThat(linhas()).isEqualTo(50);
	}

	@Test
	void listaVaziaAbortaSemDespublicarNada() {
		semiar(1, "a", true);
		semiar(2, "b", true);
		pagina(1, 1);
		assertThatThrownBy(() -> tarefa.executar(false)).isInstanceOf(TarefaRecorrente.FalhaParcial.class)
				.hasMessage("abortada, nada foi gravado");
		abortaSemGravar();
	}

	@Test
	void wordPressForaNaPrimeiraPaginaAborta() {
		semiar(1, "a", true);
		semiar(2, "b", true);
		WP.stubFor(get(urlPathEqualTo(CAMINHO)).willReturn(serviceUnavailable()));
		assertThatThrownBy(() -> tarefa.executar(false)).isInstanceOf(TarefaRecorrente.FalhaParcial.class)
				.hasMessage("abortada, nada foi gravado").hasCauseInstanceOf(ClienteWordPress.Indisponivel.class);
		abortaSemGravar();
	}

	@Test
	void dryRunNaoGrava() {
		semiar(1, "a", true);
		semiar(2, "b", true);
		pagina(1, 1, post(1, "a", false), post(9, "novo", false));
		assertThat(tarefa.executar(true)).isEqualTo("1 post(s) criados ou atualizados, 1 deixariam de estar publicados (simulação)");
		abortaSemGravar();
	}
}
