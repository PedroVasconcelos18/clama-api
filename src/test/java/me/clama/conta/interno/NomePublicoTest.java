package me.clama.conta.interno;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Regra do nome publico do autor (INV:43): nunca expoe e-mail; vazio vira "Pedro". */
class NomePublicoTest {

	@Test
	void semNomeViraPedro() {
		assertThat(NomePublico.de(null, "compacto")).isEqualTo("Pedro");
		assertThat(NomePublico.de("", "compacto")).isEqualTo("Pedro");
		assertThat(NomePublico.de("   \t ", "completo")).isEqualTo("Pedro");
	}

	@Test
	void completoPreservaEspacosInternosEApara() {
		assertThat(NomePublico.de("  Juliana   Silva ", "completo")).isEqualTo("Juliana   Silva");
	}

	@Test
	void compactoUmaPalavraEElaMesma() {
		assertThat(NomePublico.de("Juliana", "compacto")).isEqualTo("Juliana");
	}

	@Test
	void compactoPrimeiraMaisInicialDaUltimaSemMudarCaixa() {
		assertThat(NomePublico.de("Juliana Silva", "compacto")).isEqualTo("Juliana S.");
		assertThat(NomePublico.de("ana maria", "compacto")).isEqualTo("ana m.");
		assertThat(NomePublico.de("Ana\tMaria  de Souza", "compacto")).isEqualTo("Ana S.");
		assertThat(NomePublico.de("Ana Maria", "compacto")).isEqualTo("Ana M.");
	}

	@Test
	void formatoDesconhecidoOuNuloEhCompacto() {
		assertThat(NomePublico.de("Juliana Silva", "outro")).isEqualTo("Juliana S.");
		assertThat(NomePublico.de("Juliana Silva", null)).isEqualTo("Juliana S.");
	}

	@Test
	void deUsuario() {
		var u = Usuario.novo("a@b.com", "Juliana Silva", Papel.CLIENTE, java.time.Instant.EPOCH);
		assertThat(NomePublico.de(u)).isEqualTo("Juliana S.");
		u.escolherFormatoBlog("completo");
		assertThat(NomePublico.de(u)).isEqualTo("Juliana Silva");
		assertThat(NomePublico.de(null)).isEqualTo("Pedro");
	}
}
