package me.clama.infra.validacao;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import org.junit.jupiter.api.Test;

class ValidacaoTest {

	record Doc(@CpfOuCnpj String valor) {}

	record Mail(@EmailNaoDescartavel String valor) {}

	private final Validator v = Validation.buildDefaultValidatorFactory().getValidator();

	private List<String> erros(Object o) {
		return v.validate(o).stream().map(c -> c.getMessage()).toList();
	}

	@Test
	void cpfECnpjValidosComOuSemMascara() {
		assertThat(erros(new Doc("529.982.247-25"))).isEmpty();
		assertThat(erros(new Doc("52998224725"))).isEmpty();
		assertThat(erros(new Doc("11.222.333/0001-81"))).isEmpty();
		assertThat(erros(new Doc(null))).isEmpty();
		assertThat(erros(new Doc(""))).isEmpty();
	}

	@Test
	void cpfCnpjInvalidosComMensagemPorTipo() {
		assertThat(erros(new Doc("529.982.247-24"))).containsExactly("Confira seu CPF — parece que tem algum dígito errado.");
		assertThat(erros(new Doc("111.111.111-11"))).containsExactly("Confira seu CPF — parece que tem algum dígito errado.");
		assertThat(erros(new Doc("11.222.333/0001-82"))).containsExactly("Confira seu CNPJ — parece que tem algum dígito errado.");
		assertThat(erros(new Doc("1234"))).containsExactly("CPF deve ter 11 dígitos ou CNPJ deve ter 14 dígitos.");
		assertThat(erros(new Doc("abc"))).containsExactly("CPF deve ter 11 dígitos ou CNPJ deve ter 14 dígitos.");
	}

	@Test
	void emailDescartavel() {
		var msg = "Use um e-mail principal para receber sua oração — e-mails temporários não funcionam aqui.";
		assertThat(erros(new Mail("a@10minutemail.com"))).containsExactly(msg);
		assertThat(erros(new Mail(" A@Sub.10MinuteMail.com "))).containsExactly(msg);
		assertThat(erros(new Mail("a@gmail.com"))).isEmpty();
		assertThat(erros(new Mail("a@com"))).isEmpty();
		assertThat(erros(new Mail("sem-arroba"))).isEmpty();
		assertThat(erros(new Mail(null))).isEmpty();
	}
}
