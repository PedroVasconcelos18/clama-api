package me.clama.infra.validacao;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public class EmailNaoDescartavelValidator implements ConstraintValidator<EmailNaoDescartavel, String> {

	/** Lida uma vez, na primeira validacao. */
	private static final class Dominios {
		static final Set<String> LISTA = carregar();

		private static Set<String> carregar() {
			try (var in = EmailNaoDescartavelValidator.class.getResourceAsStream("/disposable_email_domains.txt")) {
				return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().map(String::strip)
						.filter(l -> !l.isEmpty() && !l.startsWith("#")).collect(Collectors.toUnmodifiableSet());
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
	}

	@Override
	public boolean isValid(String email, ConstraintValidatorContext ctx) {
		if (email == null) return true;
		String e = email.strip().toLowerCase(Locale.ROOT);
		int arroba = e.lastIndexOf('@');
		if (arroba < 0) return true;
		String dominio = e.substring(arroba + 1).strip();
		// o dominio e cada sufixo ancestral (subdominios casam); o TLD sozinho nunca
		for (String d = dominio; d.contains("."); d = d.substring(d.indexOf('.') + 1)) {
			if (Dominios.LISTA.contains(d)) return false;
		}
		return true;
	}
}
