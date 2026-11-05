package me.clama.api.identidade.senha;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Senhas no formato do Django: le argon2, pbkdf2_sha256 e pbkdf2_sha1; grava argon2 (rollback le de volta). */
@Component
public class SenhaDjango implements PasswordEncoder {
	private static final String ARGON2 = "argon2";
	private static final String PREFIXO_ARGON2 = "argon2$argon2id$v=19$m=102400,t=2,p=8$";
	// saltLength 16, hashLength 32, parallelism 8, memory 102400 KiB, iterations 2 (Django 4.2)
	private final Argon2PasswordEncoder argon2 = new Argon2PasswordEncoder(16, 32, 8, 102400, 2);

	@Override
	public String encode(CharSequence senha) {
		return ARGON2 + argon2.encode(senha);
	}

	@Override
	public boolean matches(CharSequence senha, String hash) {
		if (hash == null || hash.isEmpty() || hash.startsWith("!")) {
			return false;
		}
		try {
			if (hash.startsWith(ARGON2 + "$")) {
				return argon2.matches(senha, hash.substring(ARGON2.length()));
			}
			String[] p = hash.split("\\$", -1);
			if (p.length != 4) {
				return false;
			}
			String alg = switch (p[0]) {
				case "pbkdf2_sha256" -> "PBKDF2WithHmacSHA256";
				case "pbkdf2_sha1" -> "PBKDF2WithHmacSHA1";
				default -> null;
			};
			if (alg == null) {
				return false;
			}
			byte[] esperado = Base64.getDecoder().decode(p[3]);
			var spec = new PBEKeySpec(senha.toString().toCharArray(), p[2].getBytes(StandardCharsets.UTF_8),
					Integer.parseInt(p[1]), esperado.length * 8);
			byte[] obtido = SecretKeyFactory.getInstance(alg).generateSecret(spec).getEncoded();
			return MessageDigest.isEqual(obtido, esperado);
		} catch (Exception e) {
			return false;
		}
	}

	@Override
	public boolean upgradeEncoding(String hash) {
		return hash == null || !hash.startsWith(PREFIXO_ARGON2);
	}
}
