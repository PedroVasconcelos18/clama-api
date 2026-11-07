package me.clama.api.blog;

import static org.assertj.core.api.Assertions.assertThat;

import me.clama.api.identidade.Usuario;
import org.junit.jupiter.api.Test;

/** _autor_nome_publico (mapa 0.7). */
class NomePublicoTest {
    private static Usuario u(String nome, String formato) {
        Usuario u = new Usuario();
        u.setNomeCompleto(nome);
        u.setNomeFormatBlog(formato);
        return u;
    }

    @Test
    void semNomeViraPedro() {
        assertThat(NomePublico.de(null)).isEqualTo("Pedro");
        assertThat(NomePublico.de(u("", "compacto"))).isEqualTo("Pedro");
        assertThat(NomePublico.de(u("   \t ", "completo"))).isEqualTo("Pedro");
    }

    @Test
    void completoPreservaEspacosInternosEApara() {
        assertThat(NomePublico.de(u("  Juliana   Silva ", "completo"))).isEqualTo("Juliana   Silva");
    }

    @Test
    void compactoUmaPalavraEElaMesma() {
        assertThat(NomePublico.de(u("Juliana", "compacto"))).isEqualTo("Juliana");
    }

    @Test
    void compactoPrimeiraMaisInicialDaUltimaSemMudarCaixa() {
        assertThat(NomePublico.de(u("Juliana Silva", "compacto"))).isEqualTo("Juliana S.");
        assertThat(NomePublico.de(u("ana maria", "compacto"))).isEqualTo("ana m.");
        assertThat(NomePublico.de(u("Ana\tMaria  de Souza", "compacto"))).isEqualTo("Ana S.");
    }

    @Test
    void formatoDesconhecidoOuNuloEhCompacto() {
        assertThat(NomePublico.de(u("Juliana Silva", "outro"))).isEqualTo("Juliana S.");
        assertThat(NomePublico.de(u("Juliana Silva", null))).isEqualTo("Juliana S.");
    }
}
