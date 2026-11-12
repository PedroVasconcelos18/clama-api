package me.clama.infra.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(RotasDeTeste.class)
@Import(TratadorDeErros.class)
@AutoConfigureMockMvc(addFilters = false)
class TratadorDeErrosTest {

    private static final String GENERICA = "Algo não saiu como esperado. Tente novamente em instantes.";

    @Autowired
    MockMvcTester mvc;

    /** O slice varre o filtro do limite de sessao (conta.web), que pede o relogio. */
    @MockitoBean
    java.time.Clock relogio;

    @Test
    void erroDeNegocio() {
        var r = mvc.get().uri("/teste/negocio");
        assertThat(r).hasStatus(409).hasContentType("application/problem+json");
        assertThat(r).bodyJson().extractingPath("$.codigo").isEqualTo("ja_pago");
        assertThat(r).bodyJson().extractingPath("$.mensagemPastoral").isEqualTo("Este pedido já foi pago.");
        assertThat(r).bodyJson().extractingPath("$.detail").isEqualTo("Pedido já pago.");
    }

    @Test
    void metodoNaoPermitido() {
        var r = mvc.post().uri("/teste/negocio");
        assertThat(r).hasStatus(405).hasContentType("application/problem+json");
        assertThat(r).bodyJson().extractingPath("$.codigo").isEqualTo("metodo_nao_permitido");
        assertThat(r).bodyJson().extractingPath("$.mensagemPastoral").isEqualTo(GENERICA);
    }

    @Test
    void midiaNaoSuportada() {
        var r = mvc.post().uri("/teste/validacao").contentType(MediaType.TEXT_PLAIN).content("x");
        assertThat(r).hasStatus(415).hasContentType("application/problem+json");
        assertThat(r).bodyJson().extractingPath("$.codigo").isEqualTo("tipo_de_midia_nao_suportado");
        assertThat(r).bodyJson().extractingPath("$.mensagemPastoral").isEqualTo(GENERICA);
    }

    @Test
    void validacaoEmPortugues() {
        var r = mvc.post().uri("/teste/validacao").header("Accept-Language", "en")
                .contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"\",\"email\":\"x\"}");
        assertThat(r).hasStatus(400).hasContentType("application/problem+json").bodyJson().extractingPath("$.codigo").isEqualTo("dados_invalidos");
        assertThat(r).bodyJson().extractingPath("$.erros[*].campo").asList().contains("nome", "email");
        assertThat(r).bodyJson().extractingPath("$.erros[?(@.campo=='nome')].mensagem").asList()
                .containsExactly("não deve estar em branco");
        assertThat(r).bodyJson().extractingPath("$.erros[?(@.campo=='email')].mensagem").asList()
                .containsExactly("deve ser um endereço de e-mail bem formado");
    }

    @Test
    void jsonQuebrado() {
        assertThat(mvc.post().uri("/teste/validacao").contentType(MediaType.APPLICATION_JSON).content("{"))
                .hasStatus(400).hasContentType("application/problem+json").bodyJson().extractingPath("$.codigo").isEqualTo("corpo_invalido");
    }

    @Test
    void rotaInexistente() {
        assertThat(mvc.get().uri("/teste/nao-existe"))
                .hasStatus(404).hasContentType("application/problem+json").bodyJson().extractingPath("$.codigo").isEqualTo("nao_encontrado");
    }

    @Test
    void falhaInesperadaNaoVaza() {
        var r = mvc.get().uri("/teste/falha");
        assertThat(r).hasStatus(500).hasContentType("application/problem+json").bodyJson().extractingPath("$.codigo").isEqualTo("erro_interno");
        assertThat(r).bodyText().doesNotContain("segredo interno").doesNotContain("IllegalStateException");
        assertThat(r).bodyJson().extractingPath("$.mensagemPastoral").isEqualTo(GENERICA);
        assertThat(r).bodyJson().extractingPath("$.detail").isEqualTo("Erro interno.");
    }
}
