package me.clama.infra.cripto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class HmacDeIdentificadorTest {

	private static final String K = Base64.getEncoder().encodeToString(new byte[32]);
	private final HmacDeIdentificador h = new HmacDeIdentificador(K);

	@Test
	void vetorConhecido() {
		// HMAC-SHA256(chave de 32 zeros, "abc"), calculado a parte com openssl
		assertThat(h.hash("abc")).isEqualTo("fd7adb152c05ef80dccf50a1fa4c05d5a3ec6da95575fc312ae7c5d091836351");
	}

	@Test
	void emailGmailEquivalente() {
		var base = h.email("joaosilva@gmail.com");
		assertThat(h.email("  Joao.Silva+oracao@GoogleMail.com ")).isEqualTo(base);
		assertThat(h.email("joao.silva@outro.com")).isNotEqualTo(h.email("joaosilva@outro.com"));
		assertThat(h.email("A@Outro.com")).isEqualTo(h.email("a@outro.com"));
		assertThat(base).hasSize(64).matches("[0-9a-f]+");
	}

	@Test
	void cpfETelefoneSoDigitos() {
		assertThat(h.cpf("529.982.247-25")).isEqualTo(h.cpf("52998224725"));
		assertThat(h.telefone("(11) 91234-5678")).isEqualTo(h.telefone("11912345678"));
		assertThat(h.cpf("")).isEqualTo(h.cpf(null));
	}

	@Test
	void chaveObrigatoria32Bytes() {
		assertThatThrownBy(() -> new HmacDeIdentificador("")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new HmacDeIdentificador(Base64.getEncoder().encodeToString(new byte[8])))
				.isInstanceOf(IllegalStateException.class);
	}
}
