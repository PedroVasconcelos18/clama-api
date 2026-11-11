package me.clama.infra.formato;

import java.text.NumberFormat;
import java.util.Locale;

/** Formato de dinheiro das mensagens e e-mails. */
public final class Dinheiro {

	private Dinheiro() {}

	/** "R$ 1.234,50". */
	public static String reais(long centavos) {
		return "R$ " + NumberFormat.getIntegerInstance(Locale.of("pt", "BR")).format(centavos / 100)
				+ String.format(",%02d", Math.abs(centavos % 100));
	}
}
