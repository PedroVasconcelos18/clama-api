package me.clama.api.catalogo.plano;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import me.clama.api.BancoDeTeste;
import me.clama.api.comum.Dinheiro;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** O que o contrato gravado nao cobre: throttle anonimo da lista publica e o formato de Dinheiro. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "LIMITE_ANONIMO_ATIVO=true")
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class PlanoAdminTest {
    @Value("${local.server.port}")
    int porta;

    @Test
    void listaPublicaTemThrottleAnonimoDe60PorMinuto() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/api/planos/"))
                .header("X-Forwarded-For", "203.0.113.50").build();
        for (int i = 0; i < 60; i++) {
            assertThat(http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        }
        assertThat(http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(429);
    }

    @Test
    void dinheiroFormataComoOMoneyPy() {
        assertThat(Dinheiro.centavosParaReaisStr(0)).isEqualTo("R$ 0,00");
        assertThat(Dinheiro.centavosParaReaisStr(1)).isEqualTo("R$ 0,01");
        assertThat(Dinheiro.centavosParaReaisStr(2000)).isEqualTo("R$ 20,00");
        assertThat(Dinheiro.centavosParaReaisStr(12345)).isEqualTo("R$ 123,45");
    }
}
