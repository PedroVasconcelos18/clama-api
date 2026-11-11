package me.clama.catalogo.interno;

import static me.clama.catalogo.Complexidade.*;
import static org.assertj.core.api.Assertions.assertThat;

import me.clama.BancoDeTeste;
import me.clama.catalogo.Complexidade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

/** INV:62 sobre tres planos comuns, um inativo e os dois tecnicos da migration (que nunca entram na inferencia). */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ BancoDeTeste.class, InferenciaDePlano.class })
class InferenciaDePlanoTest {

	@Autowired InferenciaDePlano inferencia;
	@Autowired PlanoRepository planos;

	private void plano(String nome, long valor, Complexidade c, int ordem, boolean ativo) {
		var p = Plano.comum(nome, "", valor, c, ordem);
		if (!ativo) p.desativar();
		planos.save(p);
	}

	@BeforeEach
	void cenario() {
		plano("Pedido", 2000, SIMPLES, 1, true);
		plano("Versiculo", 5000, COM_VERSICULO, 2, true);
		plano("Reflexao", 10000, COM_PROFECIA_E_VERSICULOS, 3, true);
		plano("Antigo", 1500, SIMPLES, 0, false);
		planos.flush();
	}

	private String nome(long centavos) {
		return inferencia.inferirPorValor(centavos).map(Plano::getNome).orElse(null);
	}

	@Test
	void limitesDeCadaFaixa() {
		assertThat(nome(2000)).isEqualTo("Pedido");
		assertThat(nome(4999)).isEqualTo("Pedido");
		assertThat(nome(5000)).isEqualTo("Versiculo");
		assertThat(nome(9999)).isEqualTo("Versiculo");
		assertThat(nome(10000)).isEqualTo("Reflexao");
		assertThat(nome(999999)).isEqualTo("Reflexao");
	}

	@Test
	void abaixoDeTodosCaiNoMenorAtivo() {
		assertThat(nome(1)).isEqualTo("Pedido");
		assertThat(nome(1999)).isEqualTo("Pedido");
	}

	@Test
	void semComumAtivoNaoInfere() {
		planos.findAll().stream().filter(p -> !p.isTecnico()).forEach(p -> p.desativar());
		planos.flush();
		assertThat(inferencia.inferirPorValor(5000)).isEmpty();
	}
}
