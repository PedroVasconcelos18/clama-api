package me.clama.catalogo.interno;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import me.clama.infra.web.ErroDeNegocio;
import org.junit.jupiter.api.Test;

class ValidadorDeLogoTest {

	static byte[] com(byte[] cabecalho, int total) {
		var b = new byte[total];
		System.arraycopy(cabecalho, 0, b, 0, cabecalho.length);
		return b;
	}

	static byte[] ascii(String s) {
		return s.getBytes(StandardCharsets.ISO_8859_1);
	}

	static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
	static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};

	@Test
	void detectaCadaFormatoPelosBytes() {
		assertThat(ValidadorDeLogo.validar(PNG)).isEqualTo("image/png");
		assertThat(ValidadorDeLogo.validar(JPEG)).isEqualTo("image/jpeg");
		assertThat(ValidadorDeLogo.validar(ascii("GIF87a\0\0"))).isEqualTo("image/gif");
		assertThat(ValidadorDeLogo.validar(ascii("GIF89a\0\0"))).isEqualTo("image/gif");
		assertThat(ValidadorDeLogo.validar(ascii("RIFF\0\0\0\0WEBPVP8 "))).isEqualTo("image/webp");
	}

	@Test
	void poliglotaEAceitoPeloDesenhoOMimGravadoENosniffProtegem() {
		assertThat(ValidadorDeLogo.validar(ascii("GIF89a<script>alert(1)</script>"))).isEqualTo("image/gif");
	}

	@Test
	void recusaOQueNaoEImagemRaster() {
		var svg = ascii("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>");
		var textoComoPng = "isto nao e um png".getBytes(StandardCharsets.UTF_8);
		for (var b : new byte[][] {svg, textoComoPng, ascii("RIFF\0\0\0\0WAVEfmt "), ascii("GIF88a"), new byte[] {1}}) {
			assertThatThrownBy(() -> ValidadorDeLogo.validar(b)).isInstanceOfSatisfying(ErroDeNegocio.class, e -> {
				assertThat(e.codigo()).isEqualTo("logo_invalida");
				assertThat(e.erros().get(0).campo()).isEqualTo("logo");
				assertThat(e.getMessage()).isEqualTo("Formato de imagem não suportado. Use PNG, JPG, WEBP ou GIF.");
			});
		}
	}

	@Test
	void vazioEParteAusente() {
		assertThatThrownBy(() -> ValidadorDeLogo.validar(new byte[0])).hasMessage("O arquivo submetido está vazio.");
		assertThatThrownBy(() -> ValidadorDeLogo.validar(null)).hasMessageStartingWith("O dado submetido não era um arquivo.");
	}

	@Test
	void limiteDe250KbExato() {
		assertThat(ValidadorDeLogo.validar(com(PNG, 256000))).isEqualTo("image/png");
		assertThatThrownBy(() -> ValidadorDeLogo.validar(com(PNG, 256001)))
				.hasMessage("A imagem excede o limite de 250 KB. Envie uma versão menor.");
	}
}
