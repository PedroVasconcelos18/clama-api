package me.clama.pagamento.interno.relatorio;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A abreviacao do nome e a referencia do pedido: as mesmas do Django ({@code abreviar_nome}, {@code referencia_pedido}). */
class NomeEReferenciaTest {

	@Test
	void abreviaPrimeiroNomeEInicialDoUltimo() {
		assertThat(RelatorioDeRepasse.abreviar("Maria Silva Santos")).isEqualTo("Maria S.");
		assertThat(RelatorioDeRepasse.abreviar("  Maria   Silva\tSantos  ")).isEqualTo("Maria S.");
		assertThat(RelatorioDeRepasse.abreviar("Maria Santos")).isEqualTo("Maria S."); // espaco sem quebra: o split() do Python tambem corta
		assertThat(RelatorioDeRepasse.abreviar("Ana Souza")).isEqualTo("Ana S.");
	}

	@Test
	void nomeUnicoFicaComoEstaEVazioViraSemNome() {
		assertThat(RelatorioDeRepasse.abreviar("Maria")).isEqualTo("Maria");
		assertThat(RelatorioDeRepasse.abreviar("")).isEqualTo("(sem nome)");
		assertThat(RelatorioDeRepasse.abreviar("   ")).isEqualTo("(sem nome)");
		assertThat(RelatorioDeRepasse.abreviar(null)).isEqualTo("(sem nome)");
	}

	@Test
	void nomeComArrobaOuDigitoViraSemNomeParaNaoVazarEmailNemCpf() {
		assertThat(RelatorioDeRepasse.abreviar("maria@exemplo.com")).isEqualTo("(sem nome)");
		assertThat(RelatorioDeRepasse.abreviar("52998224725")).isEqualTo("(sem nome)");
		assertThat(RelatorioDeRepasse.abreviar("529.982.247-25")).isEqualTo("(sem nome)");
		assertThat(RelatorioDeRepasse.abreviar("Maria maria@exemplo.com")).isEqualTo("(sem nome)");
		assertThat(RelatorioDeRepasse.abreviar("Maria 52998224725 Silva")).isEqualTo("(sem nome)");
	}

	@Test
	void acentoPreservadoEInicialMaiuscula() {
		assertThat(RelatorioDeRepasse.abreviar("Ágata Éfrem álvares")).isEqualTo("Ágata Á.");
		assertThat(RelatorioDeRepasse.abreviar("José da Conceição")).isEqualTo("José C.");
		assertThat(RelatorioDeRepasse.abreviar("João d'Ávila")).isEqualTo("João D.");
	}

	@Test
	void referenciaComQuatroPrimeirosEQuatroUltimosHex() {
		assertThat(RelatorioDeRepasse.referencia(UUID.fromString("a3f2c0de-1111-4222-8333-44445555 8b1c".replace(" ", "")))).isEqualTo("#a3f2…8b1c");
		assertThat(RelatorioDeRepasse.referencia(UUID.fromString("00010000-0000-4000-8000-00000000ffff"))).isEqualTo("#0001…ffff");
	}
}
