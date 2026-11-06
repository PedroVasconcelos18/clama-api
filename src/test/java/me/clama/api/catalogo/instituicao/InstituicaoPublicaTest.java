package me.clama.api.catalogo.instituicao;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import me.clama.api.BancoDeTeste;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** O que o contrato gravado nao cobre: throttle anonimo e cookie invalido na lista publica. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "LIMITE_ANONIMO_ATIVO=true")
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class InstituicaoPublicaTest {
    @Value("${local.server.port}")
    int porta;

    @Test
    void listaPublicaTemThrottleAnonimoDe60PorMinuto() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/api/instituicoes/"))
                .header("X-Forwarded-For", "203.0.113.51").build();
        for (int i = 0; i < 60; i++) {
            assertThat(http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        }
        assertThat(http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(429);
    }

    @Test
    void cookieInvalidoNaListaPublicaE401() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/api/instituicoes/"))
                .header("X-Forwarded-For", "203.0.113.52").header("Cookie", "clama_access=lixo").build();
        assertThat(HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(401);
    }
}
