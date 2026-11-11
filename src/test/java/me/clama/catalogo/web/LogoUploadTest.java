package me.clama.catalogo.web;

import static me.clama.conta.web.Navegador.emailUnico;
import static me.clama.conta.web.Navegador.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import me.clama.BancoDeTeste;
import me.clama.conta.interno.Papel;
import me.clama.conta.interno.UsuarioRepository;
import me.clama.conta.web.Navegador;
import me.clama.infra.seguranca.LimiteDeTaxa;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(BancoDeTeste.class)
class LogoUploadTest {

	static final String SENHA = "senha-forte-123";
	static final byte[] PNG1 = comCabecalho(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1}, 50);
	static final byte[] PNG2 = comCabecalho(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 2}, 60);

	@LocalServerPort int porta;
	@Autowired UsuarioRepository usuarios;
	@Autowired PasswordEncoder encoder;
	@Autowired LimiteDeTaxa limite;
	@Autowired JdbcClient jdbc;
	final List<Long> criados = new ArrayList<>();

	static byte[] comCabecalho(byte[] c, int total) {
		var b = new byte[total];
		System.arraycopy(c, 0, b, 0, c.length);
		return b;
	}

	@BeforeEach
	@AfterEach
	void limpar() {
		limite.limpar();
		jdbc.sql("delete from instituicao_logo").update();
		jdbc.sql("delete from instituicao").update();
		jdbc.sql("delete from auditoria where entidade = 'instituicao'").update();
		criados.forEach(id -> {
			jdbc.sql("delete from auditoria where autor_id = :id").param("id", id).update();
			jdbc.sql("delete from usuario where id = :id").param("id", id).update();
		});
		criados.clear();
	}

	private Navegador entrar(Papel papel) throws Exception {
		var u = Navegador.usuario(usuarios, encoder, emailUnico(), SENHA, papel);
		criados.add(u.getId());
		var nav = new Navegador(porta);
		assertThat(nav.login(u.getEmail(), SENHA).statusCode()).isEqualTo(200);
		return nav;
	}

	private long instituicao(Navegador admin) throws Exception {
		return json(admin.post("/api/admin/instituicoes", "{\"nome\":\"Casa\"}")).get("id").asLong();
	}

	private HttpResponse<byte[]> publica(long id) throws Exception {
		return HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/api/instituicoes/" + id + "/logo")).build(),
				HttpResponse.BodyHandlers.ofByteArray());
	}

	private static String caminho(long id) {
		return "/api/admin/instituicoes/" + id + "/logo";
	}

	private String logoUrlDaLista(Navegador admin, long id) throws Exception {
		for (var n : json(admin.get("/api/admin/instituicoes"))) {
			if (n.get("id").asLong() == id) return n.get("logoUrl").isNull() ? null : n.get("logoUrl").asString();
		}
		throw new AssertionError("instituicao sumiu");
	}

	private String listaPublicaUrl(long id) throws Exception {
		var res = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/api/instituicoes")).build(),
				HttpResponse.BodyHandlers.ofString());
		for (var n : json(res)) if (n.get("id").asLong() == id) return n.get("logoUrl").asString();
		throw new AssertionError("instituicao sumiu da lista publica");
	}

	private String auditoriaDoLogo(long id) {
		return jdbc.sql("select diff::text from auditoria where entidade = 'instituicao' and entidade_id = :id and acao = 'MUDANCA' order by id desc limit 1")
				.param("id", Long.toString(id)).query(String.class).single();
	}

	@Test
	void trocaServeOsNovosBytesComNovoEtagEMudaOLogoUrl() throws Exception {
		var admin = entrar(Papel.ADMIN);
		var id = instituicao(admin);
		assertThat(logoUrlDaLista(admin, id)).isNull();

		var res = admin.putArquivo(caminho(id), "logo", "a.png", "image/jpeg", PNG1); // MIME declarado mente
		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(json(res).get("temLogo").asBoolean()).isTrue();
		var primeira = publica(id);
		assertThat(primeira.statusCode()).isEqualTo(200);
		assertThat(primeira.body()).isEqualTo(PNG1);
		assertThat(primeira.headers().firstValue("Content-Type")).hasValue("image/png"); // detectado, nao declarado
		var etag1 = primeira.headers().firstValue("ETag").orElseThrow();
		var url1 = logoUrlDaLista(admin, id);
		var publica1 = listaPublicaUrl(id);
		assertThat(auditoriaDoLogo(id)).contains("logo").contains("\"antes\": null").doesNotContain("PNG");

		assertThat(admin.putArquivo(caminho(id), "logo", "b.png", "image/png", PNG2).statusCode()).isEqualTo(200);
		var segunda = publica(id);
		assertThat(segunda.body()).isEqualTo(PNG2);
		assertThat(segunda.headers().firstValue("ETag").orElseThrow()).isNotEqualTo(etag1);
		assertThat(logoUrlDaLista(admin, id)).isNotEqualTo(url1);
		assertThat(listaPublicaUrl(id)).isNotEqualTo(publica1);
		assertThat(auditoriaDoLogo(id)).doesNotContain("\"antes\": null");
	}

	@Test
	void poliglotaGifComScriptEServidoComoGifNosniff() throws Exception {
		var admin = entrar(Papel.ADMIN);
		var id = instituicao(admin);
		var poliglota = "GIF89a<script>alert(1)</script>".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
		assertThat(admin.putArquivo(caminho(id), "logo", "x.html", "text/html", poliglota).statusCode()).isEqualTo(200);
		var res = publica(id);
		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(res.headers().firstValue("Content-Type")).hasValue("image/gif");
		assertThat(res.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
	}

	@Test
	void removerDa404NaPublicaEERepeticaoEIdempotente() throws Exception {
		var admin = entrar(Papel.ADMIN);
		var id = instituicao(admin);
		admin.putArquivo(caminho(id), "logo", "a.png", "image/png", PNG1);

		assertThat(admin.delete(caminho(id)).statusCode()).isEqualTo(204);
		assertThat(publica(id).statusCode()).isEqualTo(404);
		assertThat(logoUrlDaLista(admin, id)).isNull();
		assertThat(admin.delete(caminho(id)).statusCode()).isEqualTo(204);
		assertThat(admin.delete(caminho(999999)).statusCode()).isEqualTo(404);
	}

	@Test
	void invalidasDao400LogoInvalidaNoCampoLogo() throws Exception {
		var admin = entrar(Papel.ADMIN);
		var id = instituicao(admin);
		var svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes();
		for (var b : List.of(svg, new byte[0], comCabecalho(PNG1, 256001))) {
			var res = admin.putArquivo(caminho(id), "logo", "x.png", "image/png", b);
			assertThat(res.statusCode()).isEqualTo(400);
			assertThat(json(res).get("codigo").asString()).isEqualTo("logo_invalida");
			assertThat(json(res).get("erros").get(0).get("campo").asString()).isEqualTo("logo");
		}
		var semParte = admin.putArquivo(caminho(id), "outro", "x.png", "image/png", PNG1);
		assertThat(semParte.statusCode()).isEqualTo(400);
		assertThat(json(semParte).get("codigo").asString()).isEqualTo("logo_invalida");
		assertThat(publica(id).statusCode()).isEqualTo(404);
	}

	@Test
	void estouroDoLimiteDoMultipartDa400SemErro500() throws Exception {
		var admin = entrar(Papel.ADMIN);
		var id = instituicao(admin);
		var res = admin.putArquivo(caminho(id), "logo", "x.png", "image/png", comCabecalho(PNG1, 500_000));
		assertThat(res.statusCode()).isEqualTo(400);
		assertThat(json(res).get("codigo").asString()).isEqualTo("arquivo_grande_demais");
	}

	@Test
	void clienteRecebe403AnonimoRecebe401() throws Exception {
		var admin = entrar(Papel.ADMIN);
		var id = instituicao(admin);
		var cliente = entrar(Papel.CLIENTE);
		assertThat(cliente.putArquivo(caminho(id), "logo", "a.png", "image/png", PNG1).statusCode()).isEqualTo(403);
		assertThat(cliente.delete(caminho(id)).statusCode()).isEqualTo(403);
		var anonimo = new Navegador(porta);
		anonimo.get("/api/auth/eu"); // emite o XSRF-TOKEN: o 401 vem da autenticacao, nao do CSRF
		assertThat(anonimo.putArquivo(caminho(id), "logo", "a.png", "image/png", PNG1).statusCode()).isEqualTo(401);
		assertThat(anonimo.delete(caminho(id)).statusCode()).isEqualTo(401);
	}

	@Test
	void semCsrfDa403CsrfInvalido() throws Exception {
		var admin = entrar(Papel.ADMIN);
		var id = instituicao(admin);
		var semToken = new Navegador(porta); // nunca fez GET: sem cookie nem header XSRF
		var res = semToken.putArquivo(caminho(id), "logo", "a.png", "image/png", PNG1);
		assertThat(res.statusCode()).isEqualTo(403);
		assertThat(json(res).get("codigo").asString()).isEqualTo("csrf_invalido");
		assertThat(publica(id).statusCode()).isEqualTo(404);
	}
}
