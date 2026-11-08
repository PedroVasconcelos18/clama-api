package me.clama.infra.seguranca;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Hash argon2 real do Django (mapa onda 2, par. 3): a migracao troca o prefixo "argon2" por "{argon2}". */
class ConversaoDeSenhaDjangoTest {

	static final String DJANGO = "argon2$argon2id$v=19$m=102400,t=2,p=8$eXR6YUpaeTZHQ3BGR0dDQ1JXNmxNRg$/nedzpnCFv184OKLaRq2IP2CN7SflMwURGefQ6lTkeg";
	static final String CONVERTIDO = "{argon2}" + DJANGO.substring("argon2".length());

	private final PasswordEncoder codificador = new CodificadorDeSenha().passwordEncoder();

	@Test
	void hashDoDjangoConvertidoConfere() {
		assertTrue(codificador.matches("Clama-forte-2026", CONVERTIDO));
		assertFalse(codificador.matches("Clama-errada-2026", CONVERTIDO));
	}

	@Test
	void parametrosDoDjangoPedemRegravacao() {
		assertTrue(codificador.upgradeEncoding(CONVERTIDO));
	}

	@Test
	void senhaNovaSaiEmArgon2() {
		assertTrue(codificador.encode("Clama-forte-2026").startsWith("{argon2}"));
	}
}
