package me.clama.conta.interno;

import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * Nome do autor exposto no blog (INV:43): nunca o e-mail; vazio vira "Pedro". "completo" mostra o nome aparado; qualquer
 * outro formato e compacto: primeiro nome mais a inicial do ultimo ("Ana Maria de Souza" vira "Ana S.").
 */
public final class NomePublico {

	/** Espaco no sentido de str.strip()/str.split() do Python (inclui NBSP e os separadores Unicode). */
	private static final String ESPACO = "[\\t-\\r\\u001c-\\u0020\\u0085\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000]";
	private static final Pattern PONTAS = Pattern.compile("^" + ESPACO + "+|" + ESPACO + "+$");
	private static final Pattern CORRIDA = Pattern.compile(ESPACO + "+");

	private NomePublico() {}

	public static String de(Usuario u) {
		return u == null ? de(null, null) : de(u.getNomeCompleto(), u.getNomeFormatoBlog());
	}

	static String de(String nomeCompleto, String formato) {
		var nome = nomeCompleto == null ? "" : PONTAS.matcher(nomeCompleto).replaceAll("");
		if (nome.isEmpty()) return "Pedro";
		if ("completo".equals(formato)) return nome;
		var partes = CORRIDA.split(nome);
		if (partes.length == 1) return partes[0];
		var ultima = partes[partes.length - 1];
		return partes[0] + " " + ultima.substring(0, ultima.offsetByCodePoints(0, 1)) + ".";
	}
}
