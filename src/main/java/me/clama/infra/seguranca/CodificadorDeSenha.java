package me.clama.infra.seguranca;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Grava argon2 e le "{argon2}" com os parametros da propria string, o que cobre os hashes migrados do Django
 * (m=102400,p=8); {@code upgradeEncoding} manda regravar com os parametros novos no login.
 */
@Configuration
public class CodificadorDeSenha {

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new DelegatingPasswordEncoder("argon2", Map.of("argon2", new Argon2Regravavel()));
	}

	/** O Argon2PasswordEncoder nunca pede regravacao; aqui qualquer hash fora dos parametros padrao pede. */
	private static final class Argon2Regravavel extends Argon2PasswordEncoder {
		// ponytail: compara a string de parametros do padrao v5_8 (m=16384,t=2,p=1); mudar o padrao exige mudar aqui
		private static final String PARAMETROS_PADRAO = "$m=16384,t=2,p=1$";

		Argon2Regravavel() {
			super(16, 32, 1, 1 << 14, 2);
		}

		@Override
		protected boolean upgradeEncodingNonNull(String hash) {
			return !hash.contains(PARAMETROS_PADRAO);
		}
	}
}
