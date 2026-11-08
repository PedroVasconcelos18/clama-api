package me.clama.conta;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EmailCanonicoTest {

	@Test
	void gmailSemPontosESemTag() {
		assertEquals("fulano@gmail.com", EmailCanonico.de("Fu.lano+x@GoogleMail.com"));
		assertEquals("fulano@gmail.com", EmailCanonico.de("  fulano@gmail.com "));
	}

	@Test
	void outrosDominiosMantemPontosETag() {
		assertEquals("fu.lano+x@exemplo.com", EmailCanonico.de("Fu.lano+x@Exemplo.com"));
	}
}
