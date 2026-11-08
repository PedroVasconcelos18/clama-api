package me.clama;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/** Sem o springdoc ligado (producao), /v3/api-docs cai no denyAll: 401, nao 200 nem 404 com o contrato. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(BancoDeTeste.class)
class OpenApiForaDoLocalTest {

	@LocalServerPort int porta;

	@Test
	void apiDocsNegadoPorPadrao() throws Exception {
		var r = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/v3/api-docs")).build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(r.statusCode()).isEqualTo(401);
	}
}
