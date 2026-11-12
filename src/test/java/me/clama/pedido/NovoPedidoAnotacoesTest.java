package me.clama.pedido;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import jakarta.validation.Validation;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Regressao: um {@code @Size.List} no componente {@code nome} fazia o {@code Field.getAnnotatedType} falhar com
 * "Duplicate annotation" e todo pedido (pago, gratuito, doacao e freemium) respondia 500. Este teste nao precisa de
 * banco: le as anotacoes de cada campo e valida um corpo de verdade.
 */
class NovoPedidoAnotacoesTest {

	@Test
	void todoCampoTemAnotacaoDeTipoLegivel() {
		Arrays.stream(NovoPedido.class.getDeclaredFields())
				.forEach(f -> assertThatCode(f::getAnnotatedType).as(f.getName()).doesNotThrowAnyException());
	}

	@Test
	void validaNomeCurtoELongoComOsTextosPastorais() {
		try (var fabrica = Validation.buildDefaultValidatorFactory()) {
			var v = fabrica.getValidator();
			var curto = new NovoPedido("A", "a@b.com", "52998224725", null, null, null, null, null, null, null, true, null);
			var longo = new NovoPedido("A".repeat(121), "a@b.com", "52998224725", null, null, null, null, null, null, null, true, null);
			var ok = new NovoPedido("Maria", "a@b.com", "52998224725", null, null, null, null, null, null, null, true, null);
			assertThat(v.validate(curto)).extracting(c -> c.getMessage())
					.containsExactly("Conta um pouco de você no nome — pelo menos 2 letras.");
			assertThat(v.validate(longo)).extracting(c -> c.getMessage())
					.containsExactly("O nome pode ter no máximo 120 caracteres.");
			assertThat(v.validate(ok)).isEmpty();
		}
	}
}
