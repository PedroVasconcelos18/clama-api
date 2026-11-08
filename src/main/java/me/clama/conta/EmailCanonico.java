package me.clama.conta;

import java.util.Locale;

/** Forma comparavel do e-mail: minusculas e, no Gmail, sem pontos e sem "+tag" (googlemail vira gmail). */
public final class EmailCanonico {
	private EmailCanonico() {}

	public static String de(String email) {
		String e = email.strip().toLowerCase(Locale.ROOT);
		int at = e.indexOf('@');
		if (at < 0) return e;
		String local = e.substring(0, at);
		String dominio = e.substring(at + 1);
		if (!dominio.equals("gmail.com") && !dominio.equals("googlemail.com")) return e;
		int mais = local.indexOf('+');
		if (mais >= 0) local = local.substring(0, mais);
		return local.replace(".", "") + "@gmail.com";
	}
}
