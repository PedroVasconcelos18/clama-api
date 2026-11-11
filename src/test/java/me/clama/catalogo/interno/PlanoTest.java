package me.clama.catalogo.interno;

import static me.clama.catalogo.Complexidade.COM_VERSICULO;
import static me.clama.catalogo.Complexidade.SIMPLES;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import me.clama.catalogo.TipoDePlano;
import org.junit.jupiter.api.Test;

class PlanoTest {

	private static Plano tecnico() throws Exception {
		Constructor<Plano> c = Plano.class.getDeclaredConstructor();
		c.setAccessible(true);
		var p = c.newInstance();
		var tipo = Plano.class.getDeclaredField("tipo");
		tipo.setAccessible(true);
		tipo.set(p, TipoDePlano.GRATUITO);
		return p;
	}

	@Test
	void comumAceitaTodasAsMudancas() {
		var p = Plano.comum("A", null, 2000, SIMPLES, 1);
		p.renomear("B", "desc");
		p.alterarValor(3000);
		p.reordenar(5);
		p.desativar();
		p.ativar();
		assertEquals("B", p.getNome());
		assertEquals(3000, p.getValorCentavos());
		assertEquals(5, p.getOrdem());
		assertTrue(p.isAtivo());
	}

	@Test
	void tecnicoSoMudaNomeEDescricao() throws Exception {
		var p = tecnico();
		p.renomear("Novo", "d");
		assertEquals("Novo", p.getNome());
		assertThrows(PlanoTecnicoException.class, p::desativar);
		assertThrows(PlanoTecnicoException.class, () -> p.alterarValor(5));
		assertThrows(PlanoTecnicoException.class, () -> p.reordenar(5));
		assertThrows(PlanoTecnicoException.class, () -> p.alterarComplexidade(COM_VERSICULO));
	}

	@Test
	void comumExigeValorMinimoDeUmCentavo() {
		assertThrows(IllegalArgumentException.class, () -> Plano.comum("A", "", 0, SIMPLES, 1));
		assertEquals(1, Plano.comum("A", "", 1, SIMPLES, 1).getValorCentavos());
	}
}
