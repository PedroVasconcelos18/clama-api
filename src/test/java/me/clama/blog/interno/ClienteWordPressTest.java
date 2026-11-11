package me.clama.blog.interno;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.http.Fault;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

@WireMockTest
class ClienteWordPressTest {

	static final String CAMPOS = "id,slug,title,link,date_gmt,content.protected";

	ClienteWordPress cliente(WireMockRuntimeInfo wm) {
		return new ClienteWordPress(wm.getHttpBaseUrl(), Duration.ofSeconds(5));
	}

	static String post(long id, String slug, boolean protegido) {
		return "{\"id\":" + id + ",\"slug\":\"" + slug + "\",\"title\":{\"rendered\":\"Fé &#8211; e esperança\"},\"link\":\"https://blog.exemplo/" + slug
				+ "/\",\"date_gmt\":\"2026-11-01T12:30:00\",\"content\":{\"protected\":" + protegido + "}}";
	}

	@Test
	void buscaPorSlugComOsCamposDoMapaESemCredencial(WireMockRuntimeInfo wm) {
		stubFor(get(urlPathEqualTo("/wp-json/wp/v2/posts")).willReturn(okJson("[" + post(7, "fe", false) + "]")));
		var achados = cliente(wm).buscarPorSlug("fe");
		assertThat(achados).hasSize(1);
		var p = achados.get(0);
		assertThat(p.id()).isEqualTo(7);
		assertThat(p.titulo()).isEqualTo("Fé – e esperança");
		assertThat(p.url()).isEqualTo("https://blog.exemplo/fe/");
		assertThat(p.publicadoEm()).isEqualTo(Instant.parse("2026-11-01T12:30:00Z"));
		assertThat(p.publicado()).isTrue();
		verify(getRequestedFor(urlPathEqualTo("/wp-json/wp/v2/posts")).withQueryParam("slug", equalTo("fe"))
				.withQueryParam("_fields", equalTo(CAMPOS)).withoutHeader("Authorization"));
	}

	@Test
	void listaPedePaginaDe100ComOsCampos(WireMockRuntimeInfo wm) {
		stubFor(get(urlPathEqualTo("/wp-json/wp/v2/posts")).willReturn(okJson("[" + post(1, "a", false) + "," + post(2, "b", false) + "]")
				.withHeader("X-WP-TotalPages", "3")));
		var pagina = cliente(wm).listar(2);
		assertThat(pagina.posts()).hasSize(2);
		assertThat(pagina.totalPaginas()).isEqualTo(3);
		verify(getRequestedFor(urlPathEqualTo("/wp-json/wp/v2/posts")).withQueryParam("page", equalTo("2"))
				.withQueryParam("per_page", equalTo("100")).withQueryParam("_fields", equalTo(CAMPOS)));
	}

	@Test
	void conteudoProtegidoNaoEPublicado(WireMockRuntimeInfo wm) {
		stubFor(get(urlPathEqualTo("/wp-json/wp/v2/posts")).willReturn(okJson("[" + post(7, "fe", true) + "]")));
		assertThat(cliente(wm).buscarPorSlug("fe").get(0).publicado()).isFalse();
	}

	@Test
	void headerAusenteOuLixoValeUmaPagina(WireMockRuntimeInfo wm) {
		stubFor(get(urlPathEqualTo("/wp-json/wp/v2/posts")).willReturn(okJson("[]")));
		assertThat(cliente(wm).listar(1).totalPaginas()).isEqualTo(1);
		stubFor(get(urlPathEqualTo("/wp-json/wp/v2/posts")).willReturn(okJson("[]").withHeader("X-WP-TotalPages", "xx")));
		assertThat(cliente(wm).listar(1).totalPaginas()).isEqualTo(1);
	}

	@Test
	void falhasViramIndisponivelSemRetentar(WireMockRuntimeInfo wm) {
		for (var resposta : new com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder[] { notFound(), status(403), status(429),
				serverError(), serviceUnavailable() }) {
			resetAllRequests();
			stubFor(get(urlPathEqualTo("/wp-json/wp/v2/posts")).willReturn(resposta));
			assertThatThrownBy(() -> cliente(wm).buscarPorSlug("fe")).isInstanceOf(ClienteWordPress.Indisponivel.class);
			verify(1, getRequestedFor(urlPathEqualTo("/wp-json/wp/v2/posts")));
		}
	}

	@Test
	void redeTimeoutCorpoNaoJsonENaoListaViramIndisponivel(WireMockRuntimeInfo wm) {
		stubFor(get(urlPathEqualTo("/wp-json/wp/v2/posts")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
		assertThatThrownBy(() -> cliente(wm).listar(1)).isInstanceOf(ClienteWordPress.Indisponivel.class);
		stubFor(get(urlPathEqualTo("/wp-json/wp/v2/posts")).willReturn(ok().withFixedDelay(1500)));
		assertThatThrownBy(() -> new ClienteWordPress(wm.getHttpBaseUrl(), Duration.ofMillis(300)).listar(1))
				.isInstanceOf(ClienteWordPress.Indisponivel.class);
		stubFor(get(urlPathEqualTo("/wp-json/wp/v2/posts")).willReturn(ok("<html>manutencao</html>")));
		assertThatThrownBy(() -> cliente(wm).listar(1)).isInstanceOf(ClienteWordPress.Indisponivel.class);
		stubFor(get(urlPathEqualTo("/wp-json/wp/v2/posts")).willReturn(okJson("{\"code\":\"rest_error\"}")));
		assertThatThrownBy(() -> cliente(wm).listar(1)).isInstanceOf(ClienteWordPress.Indisponivel.class);
		stubFor(get(urlPathEqualTo("/wp-json/wp/v2/posts")).willReturn(okJson("[{\"slug\":\"sem-id\"}]")));
		assertThatThrownBy(() -> cliente(wm).listar(1)).isInstanceOf(ClienteWordPress.Indisponivel.class);
	}

	@Test
	void aMensagemNuncaLevaOHost(WireMockRuntimeInfo wm) {
		var host = "localhost:" + wm.getHttpPort();
		for (var resposta : new com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder[] { serverError(), ok("<html>"),
				aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER) }) {
			stubFor(get(urlPathEqualTo("/wp-json/wp/v2/posts")).willReturn(resposta));
			assertThatThrownBy(() -> cliente(wm).listar(1)).isInstanceOf(ClienteWordPress.Indisponivel.class)
					.hasMessageNotContaining(host).hasMessageNotContaining("localhost").hasMessageNotContaining("127.0.0.1");
		}
		// URL fora do ar: porta fechada
		assertThatThrownBy(() -> new ClienteWordPress("http://wp-interno.exemplo.invalid:9", Duration.ofSeconds(1)).listar(1))
				.isInstanceOf(ClienteWordPress.Indisponivel.class).hasMessageNotContaining("wp-interno");
	}

	@Test
	void semUrlConfiguradaEIndisponivel() {
		assertThatThrownBy(() -> new ClienteWordPress("", Duration.ofSeconds(1)).listar(1)).isInstanceOf(ClienteWordPress.Indisponivel.class);
	}
}
