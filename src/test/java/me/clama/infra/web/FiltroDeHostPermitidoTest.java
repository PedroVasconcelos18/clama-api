package me.clama.infra.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class FiltroDeHostPermitidoTest {

	private final FiltroDeHostPermitido filtro = new FiltroDeHostPermitido("api.clama.me, LOCALHOST");

	private MockHttpServletResponse chamar(String caminho, String host, String xForwardedHost) throws Exception {
		var req = new MockHttpServletRequest("GET", caminho);
		req.setRequestURI(caminho);
		if (host != null) req.addHeader("Host", host);
		if (xForwardedHost != null) req.addHeader("X-Forwarded-Host", xForwardedHost);
		var resp = new MockHttpServletResponse();
		filtro.doFilter(req, resp, new MockFilterChain());
		return resp;
	}

	@Test
	void hostPermitidoPassaComOuSemPortaEMaiusculas() throws Exception {
		assertThat(chamar("/api/planos", "api.clama.me", null).getStatus()).isEqualTo(200);
		assertThat(chamar("/api/planos", "API.Clama.me:443", null).getStatus()).isEqualTo(200);
		assertThat(chamar("/api/planos", "localhost:8080", null).getStatus()).isEqualTo(200);
	}

	@Test
	void hostForaDaListaOuAusenteLeva421SemCorpo() throws Exception {
		for (var host : new String[] { "clama.me", "x.up.railway.app", "api.clama.me.evil.com" }) {
			var resp = chamar("/api/planos", host, null);
			assertThat(resp.getStatus()).as("host %s", host).isEqualTo(421);
			assertThat(resp.getContentAsString()).isEmpty();
		}
	}

	@Test
	void xForwardedHostNaoAbreNada() throws Exception {
		assertThat(chamar("/api/planos", "x.up.railway.app", "api.clama.me").getStatus()).isEqualTo(421);
	}

	@Test
	void healthcheckPassaComQualquerHost() throws Exception {
		assertThat(chamar("/actuator/health/readiness", "healthcheck.railway.app", null).getStatus()).isEqualTo(200);
	}
}
