package me.clama.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import me.clama.BancoDeTeste;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(BancoDeTeste.class)
class OperacaoTest {

	@LocalServerPort
	int porta;

	private HttpResponse<String> get(String caminho) throws Exception {
		var req = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho)).build();
		return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void healthEstaUp() throws Exception {
		var r = get("/actuator/health");
		assertThat(r.statusCode()).isEqualTo(200);
		assertThat(r.body()).contains("\"status\":\"UP\"").doesNotContain("components");
	}

	@Test
	void probesDoRailway() throws Exception {
		assertThat(get("/actuator/health/liveness").statusCode()).isEqualTo(200);
		assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(200);
	}

	@Test
	void outrosEndpointsDoActuatorFechados() throws Exception {
		assertThat(get("/actuator/env").statusCode()).isIn(401, 404);
	}
}
