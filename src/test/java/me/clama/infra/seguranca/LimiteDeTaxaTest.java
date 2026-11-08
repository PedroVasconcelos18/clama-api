package me.clama.infra.seguranca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import me.clama.infra.seguranca.LimiteDeTaxa.Cota;
import me.clama.infra.web.ErroDeNegocio;
import org.junit.jupiter.api.Test;

class LimiteDeTaxaTest {

	private static Cota cota(String chave) {
		return new Cota(chave, 1, Duration.ofHours(1));
	}

	@Test
	void memoriaTemTetoEMantemOsBaldesEmUso() {
		var limite = new LimiteDeTaxa(3);
		limite.exigir(cota("alvo"));
		for (int i = 0; i < 100; i++) {
			limite.exigir(cota(i + "x".repeat(10_000))); // chaves enormes e distintas, cada balde ja gasto
			assertThatThrownBy(() -> limite.exigir(cota("alvo"))) // o balde em uso nao e despejado
					.isInstanceOf(ErroDeNegocio.class);
		}
		assertThat(limite.tamanho()).isLessThanOrEqualTo(3);
	}
}
