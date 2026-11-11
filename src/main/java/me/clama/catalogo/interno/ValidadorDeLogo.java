package me.clama.catalogo.interno;

import java.util.List;
import me.clama.infra.web.ErroDeCampo;
import me.clama.infra.web.ErroDeNegocio;
import org.springframework.http.HttpStatus;

/**
 * Valida a logo pelos BYTES (o MIME declarado pelo cliente e ignorado). Sem SVG: servido pela nossa origem,
 * executaria script. Textos do sistema antigo (INV:64), com o "vazio" sem o erro de grafia.
 */
public final class ValidadorDeLogo {

	public static final int MAXIMO_BYTES = 250 * 1024;
	public static final String MSG_NAO_ARQUIVO = "O dado submetido não era um arquivo. Cheque o tipo de codificação no formulário.";
	public static final String MSG_VAZIO = "O arquivo submetido está vazio.";
	public static final String MSG_FORMATO = "Formato de imagem não suportado. Use PNG, JPG, WEBP ou GIF.";
	public static final String MSG_TAMANHO = "A imagem excede o limite de 250 KB. Envie uma versão menor.";

	private ValidadorDeLogo() {}

	/** Devolve o MIME detectado; lanca 400 {@code logo_invalida} no campo {@code logo}. Nulo = parte ausente. */
	public static String validar(byte[] b) {
		if (b == null) throw invalida(MSG_NAO_ARQUIVO);
		if (b.length == 0) throw invalida(MSG_VAZIO);
		if (b.length > MAXIMO_BYTES) throw invalida(MSG_TAMANHO);
		if (comeca(b, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) return "image/png";
		if (comeca(b, 0xFF, 0xD8, 0xFF)) return "image/jpeg";
		if (comeca(b, 'G', 'I', 'F', '8') && b.length > 5 && (b[4] == '7' || b[4] == '9') && b[5] == 'a') return "image/gif";
		if (b.length >= 12 && comeca(b, 'R', 'I', 'F', 'F') && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
			return "image/webp";
		}
		throw invalida(MSG_FORMATO);
	}

	public static ErroDeNegocio invalida(String msg) {
		return new ErroDeNegocio(HttpStatus.BAD_REQUEST, "logo_invalida", msg, msg, List.of(new ErroDeCampo("logo", msg)));
	}

	private static boolean comeca(byte[] b, int... esperado) {
		if (b.length < esperado.length) return false;
		for (int i = 0; i < esperado.length; i++) if ((b[i] & 0xFF) != esperado[i]) return false;
		return true;
	}
}
