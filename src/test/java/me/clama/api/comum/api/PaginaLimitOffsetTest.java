package me.clama.api.comum.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** LimitOffsetPagination do DRF (default 20, max 100) e os links de replace/remove_query_param. */
class PaginaLimitOffsetTest {
    private static MockHttpServletRequest req(String query) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/admin/pedidos/");
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

    @Test
    void limiteInvalidoViraPadraoEAcimaDoMaximoVira100() {
        assertThat(PaginaLimitOffset.de(req("limit=0")).limit()).isEqualTo(20);
        assertThat(PaginaLimitOffset.de(req("limit=abc")).limit()).isEqualTo(20);
        assertThat(PaginaLimitOffset.de(req("limit=-3")).limit()).isEqualTo(20);
        assertThat(PaginaLimitOffset.de(req("limit=500")).limit()).isEqualTo(100);
        assertThat(PaginaLimitOffset.de(req(null)).limit()).isEqualTo(20);
        assertThat(PaginaLimitOffset.de(req("offset=abc")).offset()).isZero();
        assertThat(PaginaLimitOffset.de(req("offset=-1")).offset()).isZero();
    }

    @Test
    void offsetAlemDoFimTemPreviousCorreto() {
        var p = PaginaLimitOffset.de(req("offset=10"));
        var r = p.resposta(5, List.of());
        assertThat(r.keySet()).containsExactly("count", "next", "previous", "results");
        assertThat(r.get("next")).isNull();
        assertThat(r.get("previous")).isEqualTo("http://api.clama.me/api/admin/pedidos/?limit=20");
        r = PaginaLimitOffset.de(req("limit=2&offset=3")).resposta(5, List.of());
        assertThat(r.get("previous")).isEqualTo("http://api.clama.me/api/admin/pedidos/?limit=2&offset=1");
        r = PaginaLimitOffset.de(req("limit=2&status=pago%2Cerro")).resposta(5, List.of());
        assertThat(r.get("next")).isEqualTo("http://api.clama.me/api/admin/pedidos/?limit=2&offset=2&status=pago%2Cerro");
    }

    @Test
    void xForwardedProtoHttpsGeraLinksHttpsEXForwardedHostEIgnorado() {
        MockHttpServletRequest r = req("limit=1");
        r.addHeader("X-Forwarded-Proto", "https");
        r.addHeader("X-Forwarded-Host", "evil.example");
        assertThat(PaginaLimitOffset.de(r).resposta(5, List.of()).get("next"))
                .isEqualTo("https://api.clama.me/api/admin/pedidos/?limit=1&offset=1");
    }
}
