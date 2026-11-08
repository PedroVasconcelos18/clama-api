package me.clama.infra.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(RotasDeTeste.class)
@Import(TratadorDeErros.class)
@AutoConfigureMockMvc(addFilters = false)
class TratadorDeErrosTest {

    private static final String GENERICA = "Algo não saiu como esperado. Tente novamente em instantes.";

    @Autowired
    MockMvcTester mvc;

    @Test
    void erroDeNegocio() {
        assertThat(mvc.get().uri("/teste/negocio"))
                .hasStatus(409)
                .hasContentType("application/problem+json")
                .bodyJson()
                .extractingPath("$.codigo").isEqualTo("ja_pago");
        assertThat(mvc.get().uri("/teste/negocio")).bodyJson()
                .extractingPath("$.mensagemPastoral").isEqualTo("Este pedido já foi pago.");
        assertThat(mvc.get().uri("/teste/negocio")).bodyJson()
                .extractingPath("$.detail").isEqualTo("Pedido já pago.");
    }

    @Test
    void validacaoEmPortugues() {
        var r = mvc.post().uri("/teste/validacao").header("Accept-Language", "en")
                .contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"\",\"email\":\"x\"}");
        assertThat(r).hasStatus(400).bodyJson().extractingPath("$.codigo").isEqualTo("dados_invalidos");
        assertThat(r).bodyJson().extractingPath("$.erros[*].campo").asList().contains("nome", "email");
        assertThat(r).bodyJson().extractingPath("$.erros[?(@.campo=='nome')].mensagem").asList()
                .containsExactly("não deve estar em branco");
        assertThat(r).bodyJson().extractingPath("$.erros[?(@.campo=='email')].mensagem").asList()
                .containsExactly("deve ser um endereço de e-mail bem formado");
    }

    @Test
    void jsonQuebrado() {
        assertThat(mvc.post().uri("/teste/validacao").contentType(MediaType.APPLICATION_JSON).content("{"))
                .hasStatus(400).bodyJson().extractingPath("$.codigo").isEqualTo("corpo_invalido");
    }

    @Test
    void rotaInexistente() {
        assertThat(mvc.get().uri("/teste/nao-existe"))
                .hasStatus(404).bodyJson().extractingPath("$.codigo").isEqualTo("nao_encontrado");
    }

    @Test
    void falhaInesperadaNaoVaza() {
        var r = mvc.get().uri("/teste/falha");
        assertThat(r).hasStatus(500).bodyJson().extractingPath("$.codigo").isEqualTo("erro_interno");
        assertThat(r).bodyText().doesNotContain("segredo interno").doesNotContain("IllegalStateException");
        assertThat(r).bodyJson().extractingPath("$.mensagemPastoral").isEqualTo(GENERICA);
        assertThat(r).bodyJson().extractingPath("$.detail").isEqualTo("Erro interno.");
    }
}
