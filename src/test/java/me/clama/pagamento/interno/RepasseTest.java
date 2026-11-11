package me.clama.pagamento.interno;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** O calculo do repasse (INV:99): percentual do valor do pedido, arredondado para baixo. */
class RepasseTest {

	@Test
	void vinteporCentoComPiso() {
		assertThat(Repasses.valor(3001, 20)).isEqualTo(600);
		assertThat(Repasses.valor(3000, 20)).isEqualTo(600);
		assertThat(Repasses.valor(2999, 20)).isEqualTo(599);
		assertThat(Repasses.valor(4, 20)).as("abaixo de 1 centavo nao repassa").isZero();
		assertThat(Repasses.valor(Long.MAX_VALUE / 100, 100)).isEqualTo(Long.MAX_VALUE / 100);
	}

	@Test
	void percentualForaDaFaixaImpedeOBoot() {
		assertThatThrownBy(() -> new RepasseProps(101)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RepasseProps(-1)).isInstanceOf(IllegalArgumentException.class);
		assertThat(new RepasseProps(20).percentual()).isEqualTo(20);
	}
}
