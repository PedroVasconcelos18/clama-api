package me.clama.api.blog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Casos de clama/blog/tests/test_moderation.py (TestEhComentarioSuspeito). */
class ModeracaoTest {
    @Test
    void vazioENuloNaoSaoSuspeitos() {
        assertThat(Moderacao.ehSuspeito("")).isFalse();
        assertThat(Moderacao.ehSuspeito(null)).isFalse();
    }

    @Test
    void comentarioLimpo() {
        assertThat(Moderacao.ehSuspeito("Belo texto pastoral, Pedro. Me ajudou muito hoje.")).isFalse();
    }

    @Test
    void xingamentoObvio() {
        assertThat(Moderacao.ehSuspeito("Que merda de texto")).isTrue();
    }

    @Test
    void semDiferencaDeCaixa() {
        assertThat(Moderacao.ehSuspeito("MERDA total")).isTrue();
        assertThat(Moderacao.ehSuspeito("Merda total")).isTrue();
    }

    @Test
    void acentosNormalizados() {
        assertThat(Moderacao.ehSuspeito("Texto satânico aqui")).isTrue();
        assertThat(Moderacao.ehSuspeito("Texto SATÂNICO aqui")).isTrue();
        assertThat(Moderacao.ehSuspeito("o demônio")).isTrue();
    }

    @Test
    void palavraInteiraEvitaFalsoPositivo() {
        assertThat(Moderacao.ehSuspeito("Vou comprar uma cubuceta nova")).isFalse();
        assertThat(Moderacao.ehSuspeito("o cuidado do curso")).isFalse();
        assertThat(Moderacao.ehSuspeito("um cu")).isTrue();
    }

    @Test
    void palavraDentroDeParagrafoGrande() {
        String texto = "palavra ".repeat(100) + " caralho " + "fim ".repeat(50);
        assertThat(Moderacao.ehSuspeito(texto)).isTrue();
    }

    @Test
    void spamDeVariasPalavras() {
        assertThat(Moderacao.ehSuspeito("Aproveite e compre agora!")).isTrue();
        assertThat(Moderacao.ehSuspeito("Compre Agora seu curso")).isTrue();
    }

    @Test
    void spamComAcento() {
        assertThat(Moderacao.ehSuspeito("Curso grátis hoje")).isTrue();
    }

    @Test
    void listaTemAsEntradasDoDjango() {
        assertThat(Moderacao.PALAVRAS_SUSPEITAS).hasSize(36).doesNotHaveDuplicates()
                .contains("cu", "idiota", "demonio", "herege", "curso gratis", "curso grátis");
    }
}
