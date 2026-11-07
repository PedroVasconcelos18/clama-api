package me.clama.api.comum.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import me.clama.api.comum.erro.NaoEncontrado;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** PageNumberPagination do DRF (mapa 0.6). */
class PaginaPorPaginaTest {
    private static final String BASE = "http://api.clama.me/api/blog/admin/comments/";

    private static MockHttpServletRequest req(String query) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/blog/admin/comments/");
        r.addHeader("Host", "api.clama.me");
        r.setQueryString(query);
        if (query != null) {
            for (String par : query.split("&")) {
                String[] kv = par.split("=", 2);
                r.addParameter(kv[0], kv.length > 1 ? kv[1] : "");
            }
        }
        return r;
    }

    private static void invalida(String query, long count) {
        assertThatThrownBy(() -> PaginaPorPagina.de(req(query), count))
                .isInstanceOf(NaoEncontrado.class).hasMessage("Página inválida.");
    }

    @Test
    void padraoPaginaUmComVinte() {
        var p = PaginaPorPagina.de(req(null), 45);
        assertThat(p.limit()).isEqualTo(20);
        assertThat(p.offset()).isZero();
        var r = p.resposta(List.of());
        assertThat(r.keySet()).containsExactly("count", "next", "previous", "results");
        assertThat(r.get("count")).isEqualTo(45L);
        assertThat(r.get("next")).isEqualTo(BASE + "?page=2");
        assertThat(r.get("previous")).isNull();
    }

    @Test
    void pageSizeInvalidoOuNaoPositivoVira20EAcimaDeCemVira100() {
        assertThat(PaginaPorPagina.de(req("page_size=abc"), 5).limit()).isEqualTo(20);
        assertThat(PaginaPorPagina.de(req("page_size=0"), 5).limit()).isEqualTo(20);
        assertThat(PaginaPorPagina.de(req("page_size=-4"), 5).limit()).isEqualTo(20);
        assertThat(PaginaPorPagina.de(req("page_size="), 5).limit()).isEqualTo(20);
        assertThat(PaginaPorPagina.de(req("page_size=7"), 5).limit()).isEqualTo(7);
        assertThat(PaginaPorPagina.de(req("page_size=500"), 5).limit()).isEqualTo(100);
    }

    @Test
    void lastVaiParaAUltimaPagina() {
        var p = PaginaPorPagina.de(req("page=last&page_size=10"), 25);
        assertThat(p.offset()).isEqualTo(20);
        var r = p.resposta(List.of());
        assertThat(r.get("next")).isNull();
        assertThat(r.get("previous")).isEqualTo(BASE + "?page=2&page_size=10");
        assertThat(PaginaPorPagina.de(req("page=last"), 0).offset()).isZero();
    }

    @Test
    void paginaInvalidaDa404() {
        invalida("page=0", 5);
        invalida("page=abc", 5);
        invalida("page=1.0", 5);
        invalida("page=", 5);
        invalida("page=-1", 5);
        invalida("page=2", 5);
        invalida("page=3&page_size=10", 25 - 10);
        invalida("page=99999999999999999999999", 5);
        invalida("page=LAST", 5);
    }

    @Test
    void listaVaziaNaPaginaUmEValida() {
        var r = PaginaPorPagina.de(req("page=1"), 0).resposta(List.of());
        assertThat(r.get("count")).isEqualTo(0L);
        assertThat(r.get("next")).isNull();
        assertThat(r.get("previous")).isNull();
        invalida("page=2", 0);
    }

    @Test
    void previousNaPaginaDoisRemoveOPageEOsLinksSaoAlfabeticos() {
        var r = PaginaPorPagina.de(req("status=a&page=2&page_size=2"), 7).resposta(List.of());
        assertThat(r.get("previous")).isEqualTo(BASE + "?page_size=2&status=a");
        assertThat(r.get("next")).isEqualTo(BASE + "?page=3&page_size=2&status=a");
        assertThat(PaginaPorPagina.de(req("page=2"), 25).resposta(List.of()).get("previous")).isEqualTo(BASE);
        assertThat(PaginaPorPagina.de(req("page=3&page_size=10"), 25).resposta(List.of()).get("previous"))
                .isEqualTo(BASE + "?page=2&page_size=10");
    }

    @Test
    void paginaRepetidaValeAUltimaEXForwardedProtoHttpsGeraHttps() {
        MockHttpServletRequest r = req("page=1&page=2");
        r.addHeader("X-Forwarded-Proto", "https");
        r.addHeader("X-Forwarded-Host", "evil.example");
        assertThat(PaginaPorPagina.de(r, 45).resposta(List.of()).get("next"))
                .isEqualTo("https://api.clama.me/api/blog/admin/comments/?page=3");
    }
}
