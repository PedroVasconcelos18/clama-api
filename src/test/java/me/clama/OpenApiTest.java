package me.clama;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Contrato HTTP: o JSON do springdoc tem de bater com o commitado, de onde o front gera os tipos. */
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = {
		"springdoc.api-docs.enabled=true",
		// rotas dos controladores de teste (infra) ficam fora do contrato
		"springdoc.paths-to-exclude=/teste/**,/api/modulo-teste/**,/api/admin/teste/**,/api/publico/teste" })
@Import(BancoDeTeste.class)
class OpenApiTest {

	static final Path ARQUIVO = Path.of("src/test/resources/openapi/openapi.json");
	static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
			.enable(SerializationFeature.INDENT_OUTPUT).build();

	@LocalServerPort int porta;

	@Test
	void contratoIgualAoCommitado() throws Exception {
		var resposta = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/v3/api-docs")).build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(resposta.statusCode()).isEqualTo(200);
		var atual = normalizar(resposta.body());
		if (Boolean.getBoolean("openapi.atualizar")) {
			Files.writeString(ARQUIVO, atual, StandardCharsets.UTF_8);
		}
		assertThat(Files.exists(ARQUIVO)).as(comoRegenerar()).isTrue();
		assertThat(atual).as(comoRegenerar()).isEqualTo(Files.readString(ARQUIVO, StandardCharsets.UTF_8));
	}

	@Test
	void erroAparecePeloProblemDetailComAsExtensoes() throws Exception {
		var doc = JSON.readTree(Files.readString(ARQUIVO));
		var props = doc.at("/components/schemas/ProblemDetail/properties");
		assertThat(props.propertyNames()).contains("codigo", "mensagemPastoral", "erros", "detail", "status");
	}

	@Test
	void listaDeUsuariosTemPaginacaoComoParametrosDeQuery() throws Exception {
		var doc = JSON.readTree(Files.readString(ARQUIVO));
		var params = doc.at("/paths/~1api~1admin~1usuarios/get/parameters");
		var nomes = new java.util.ArrayList<String>();
		params.forEach(p -> {
			assertThat(p.get("in").asString()).isEqualTo("query");
			nomes.add(p.get("name").asString());
		});
		assertThat(nomes).contains("page", "size").doesNotContain("pagina");
	}

	/** Chaves em ordem alfabetica (deterministico) e sem {@code servers}, que traz a porta aleatoria do teste. */
	static String normalizar(String json) {
		@SuppressWarnings("unchecked")
		var raiz = (java.util.Map<String, Object>) JSON.readValue(json, Object.class);
		raiz.remove("servers");
		return JSON.writeValueAsString(raiz) + "\n";
	}

	static String comoRegenerar() {
		return "O contrato mudou. Se foi de proposito, regenere e commite: ./mvnw test -Dtest=OpenApiTest -Dopenapi.atualizar=true";
	}
}
