package me.clama.catalogo.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import me.clama.BancoDeTeste;
import me.clama.catalogo.Complexidade;
import me.clama.catalogo.interno.Instituicao;
import me.clama.catalogo.interno.InstituicaoLogo;
import me.clama.catalogo.interno.InstituicaoLogoRepository;
import me.clama.catalogo.interno.InstituicaoRepository;
import me.clama.catalogo.interno.Plano;
import me.clama.catalogo.interno.PlanoRepository;
import me.clama.infra.seguranca.LimiteDeTaxa;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(BancoDeTeste.class)
class CatalogoPublicoTest {

	static final JsonMapper JSON = JsonMapper.builder().build();
	static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};

	@LocalServerPort int porta;
	@Autowired PlanoRepository planos;
	@Autowired InstituicaoRepository instituicoes;
	@Autowired InstituicaoLogoRepository logos;
	@Autowired JdbcTemplate jdbc;
	@Autowired LimiteDeTaxa limite;
	final HttpClient http = HttpClient.newHttpClient();

	@BeforeEach
	void limpar() {
		limite.limpar();
		jdbc.update("delete from instituicao_logo");
		jdbc.update("delete from instituicao");
		jdbc.update("delete from plano where tipo = 'COMUM'");
	}

	HttpResponse<String> get(String caminho, String... headers) throws Exception {
		var req = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho));
		if (headers.length > 0) req.headers(headers);
		return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
	}

	HttpResponse<byte[]> getBytes(String caminho, String... headers) throws Exception {
		var req = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho));
		if (headers.length > 0) req.headers(headers);
		return http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
	}

	static String hash(byte[] b) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
	}

	Instituicao instituicao(String nome, int ordem, boolean ativo) {
		var i = Instituicao.nova(nome, ordem);
		if (!ativo) i.desativar();
		return instituicoes.saveAndFlush(i);
	}

	Long comLogo(Instituicao i, byte[] bytes) throws Exception {
		logos.saveAndFlush(new InstituicaoLogo(i.getId(), bytes, "image/png", hash(bytes), Instant.now()));
		return i.getId();
	}

	static void assertCacheDaLista(HttpResponse<?> res) {
		assertThat(res.headers().firstValue("Cache-Control")).hasValue("max-age=60, public, stale-while-revalidate=300");
		assertThat(res.headers().firstValue("ETag")).isPresent();
		assertThat(res.headers().allValues("Set-Cookie")).isEmpty(); // nada especifico do usuario num cache publico
	}

	@Test
	void planosSoComunsAtivosEmOrdemComOsCamposPublicos() throws Exception {
		planos.save(Plano.comum("B", "dois", 3000, Complexidade.COM_VERSICULO, 2));
		planos.save(Plano.comum("A", "um", 2000, Complexidade.SIMPLES, 1));
		var inativo = Plano.comum("Inativo", "", 5000, Complexidade.SIMPLES, 0);
		inativo.desativar();
		planos.saveAndFlush(inativo);

		var res = get("/api/planos");

		assertThat(res.statusCode()).isEqualTo(200);
		JsonNode lista = JSON.readTree(res.body());
		assertThat(lista).hasSize(2); // sem Gratuito, Livre nem inativo
		assertThat(lista.get(0).get("nome").asString()).isEqualTo("A");
		assertThat(lista.get(1).get("nome").asString()).isEqualTo("B");
		assertThat(lista.get(0).propertyNames()).containsExactlyInAnyOrder("id", "nome", "descricao", "valorCentavos",
				"complexidade");
		assertThat(lista.get(0).get("valorCentavos").asLong()).isEqualTo(2000);
		assertThat(lista.get(1).get("complexidade").asString()).isEqualTo("COM_VERSICULO");
	}

	@Test
	void cacheControlDoControllerNaoViraNoStoreESemCookie() throws Exception {
		planos.saveAndFlush(Plano.comum("A", "", 2000, Complexidade.SIMPLES, 1));
		assertCacheDaLista(get("/api/planos"));
		assertCacheDaLista(get("/api/instituicoes"));
	}

	@Test
	void ifNoneMatchIgualDa304ComOsMesmosHeadersDeCache() throws Exception {
		planos.saveAndFlush(Plano.comum("A", "", 2000, Complexidade.SIMPLES, 1));
		var etag = get("/api/planos").headers().firstValue("ETag").orElseThrow();

		var res = get("/api/planos", "If-None-Match", etag);

		assertThat(res.statusCode()).isEqualTo(304);
		assertThat(res.body()).isEmpty();
		assertThat(res.headers().firstValue("Cache-Control")).hasValue("max-age=60, public, stale-while-revalidate=300");

		planos.saveAndFlush(Plano.comum("Novo", "", 4000, Complexidade.SIMPLES, 2));
		assertThat(get("/api/planos", "If-None-Match", etag).statusCode()).isEqualTo(200); // mudou: ETag novo
	}

	@Test
	void cookieSessionInvalidoAindaDa200SemSetCookie() throws Exception {
		for (var caminho : new String[] {"/api/planos", "/api/instituicoes"}) {
			var res = get(caminho, "Cookie", "SESSION=invalida");
			assertThat(res.statusCode()).isEqualTo(200);
			assertThat(res.headers().allValues("Set-Cookie")).isEmpty();
		}
	}

	@Test
	void instituicoesSoAtivasEmOrdemComLogoUrlVersionadaOuNulo() throws Exception {
		var b = instituicao("Beta", 1, true);
		var a = instituicao("Alfa", 1, true);
		instituicao("Inativa", 0, false);
		comLogo(b, PNG);

		var res = get("/api/instituicoes");

		JsonNode lista = JSON.readTree(res.body());
		assertThat(lista).hasSize(2);
		assertThat(lista.get(0).get("nome").asString()).isEqualTo("Alfa");
		assertThat(lista.get(0).get("logoUrl").isNull()).isTrue();
		assertThat(lista.get(1).get("logoUrl").asString())
				.isEqualTo("/api/instituicoes/" + b.getId() + "/logo?v=" + hash(PNG).substring(0, 8));
		assertThat(lista.get(0).propertyNames()).containsExactlyInAnyOrder("id", "nome", "logoUrl");
		assertThat(a.getId()).isEqualTo(lista.get(0).get("id").asLong());
	}

	@Test
	void logoServeBytesComTipoNosniffEEtagImutavel() throws Exception {
		var id = comLogo(instituicao("Alfa", 1, true), PNG);

		var res = getBytes("/api/instituicoes/" + id + "/logo?v=qualquer");

		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(res.body()).isEqualTo(PNG);
		assertThat(res.headers().firstValue("Content-Type")).hasValue("image/png");
		assertThat(res.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
		assertThat(res.headers().firstValue("Cache-Control")).hasValue("max-age=86400, public, immutable");
		assertThat(res.headers().firstValue("ETag")).hasValue("\"" + hash(PNG) + "\"");
		assertThat(res.headers().allValues("Set-Cookie")).isEmpty();
		assertThat(getBytes("/api/instituicoes/" + id + "/logo", "If-None-Match", "\"" + hash(PNG) + "\"")
				.statusCode()).isEqualTo(304);
	}

	@Test
	void logoDeInstituicaoInativaAindaSai() throws Exception {
		var id = comLogo(instituicao("Antiga", 1, false), PNG); // pedido antigo ainda aponta para ela
		assertThat(getBytes("/api/instituicoes/" + id + "/logo").statusCode()).isEqualTo(200);
	}

	@Test
	void logoAusenteOuInstituicaoInexistenteDa404() throws Exception {
		var semLogo = instituicao("Sem", 1, true);
		for (var id : new Object[] {semLogo.getId(), 999999}) {
			var res = get("/api/instituicoes/" + id + "/logo");
			assertThat(res.statusCode()).isEqualTo(404);
			assertThat(JSON.readTree(res.body()).get("codigo").asString()).isEqualTo("nao_encontrado");
		}
	}

	@Test
	void sessentaPorMinutoPorIpNasTresRotas() throws Exception {
		for (int i = 0; i < 20; i++) {
			assertThat(get("/api/planos").statusCode()).isEqualTo(200);
			assertThat(get("/api/instituicoes").statusCode()).isEqualTo(200);
			assertThat(get("/api/instituicoes/1/logo").statusCode()).isEqualTo(404);
		}
		var res = get("/api/planos");
		assertThat(res.statusCode()).isEqualTo(429);
		assertThat(JSON.readTree(res.body()).get("codigo").asString()).isEqualTo("limite_excedido");
		assertThat(get("/api/instituicoes").statusCode()).isEqualTo(429);
		assertThat(get("/api/instituicoes/1/logo").statusCode()).isEqualTo(429);
		// outro IP (XFF) tem cota propria
		assertThat(get("/api/planos", "X-Forwarded-For", "203.0.113.9").statusCode()).isEqualTo(200);
	}
}
