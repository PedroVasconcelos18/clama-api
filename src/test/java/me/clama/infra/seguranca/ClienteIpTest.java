package me.clama.infra.seguranca;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClienteIpTest {

	private static String ip(String... xff) {
		var req = new MockHttpServletRequest();
		req.setRemoteAddr("10.0.0.9");
		for (var h : xff) req.addHeader("X-Forwarded-For", h);
		return ClienteIp.de(req);
	}

	@Test
	void primeiroItemValidoDoXffSenaoRemoteAddr() {
		assertThat(ip()).isEqualTo("10.0.0.9");
		assertThat(ip("203.0.113.1, 10.0.0.1")).isEqualTo("203.0.113.1");
		assertThat(ip(" lixo , 999.1.1.1,  2001:db8::1 ")).isEqualTo("2001:db8:0:0:0:0:0:1");
		assertThat(ip("lixo", "198.51.100.2")).isEqualTo("198.51.100.2");
		assertThat(ip("lixo, <script>, exemplo.com")).isEqualTo("10.0.0.9");
		assertThat(ip("")).isEqualTo("10.0.0.9");
	}
}
