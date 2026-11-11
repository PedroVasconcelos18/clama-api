package me.clama.conta.web;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import me.clama.conta.interno.Papel;
import me.clama.conta.interno.Usuario;
import me.clama.conta.interno.UsuarioRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** HttpClient real com cookie jar (o MockMvc nao reproduz cookie nem sessao); devolve o XSRF-TOKEN como a SPA. */
public final class Navegador {

	public static final JsonMapper JSON = JsonMapper.builder().build();

	private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
	private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();
	private final String base;

	public Navegador(int porta) {
		base = "http://localhost:" + porta;
	}

	public HttpResponse<String> get(String caminho, String... headers) throws Exception {
		return enviar(HttpRequest.newBuilder(URI.create(base + caminho)).GET(), headers);
	}

	public HttpResponse<String> post(String caminho, String json, String... headers) throws Exception {
		return enviarJson("POST", caminho, json, headers);
	}

	public HttpResponse<String> patch(String caminho, String json, String... headers) throws Exception {
		return enviarJson("PATCH", caminho, json, headers);
	}

	public HttpResponse<String> delete(String caminho, String... headers) throws Exception {
		var req = HttpRequest.newBuilder(URI.create(base + caminho)).DELETE();
		var xsrf = cookie("XSRF-TOKEN");
		if (xsrf != null) req.header("X-XSRF-TOKEN", xsrf);
		return enviar(req, headers);
	}

	private HttpResponse<String> enviarJson(String metodo, String caminho, String json, String... headers)
			throws Exception {
		var req = HttpRequest.newBuilder(URI.create(base + caminho))
				.header("Content-Type", "application/json")
				.method(metodo, HttpRequest.BodyPublishers.ofString(json));
		var xsrf = cookie("XSRF-TOKEN");
		if (xsrf != null) req.header("X-XSRF-TOKEN", xsrf);
		return enviar(req, headers);
	}

	/** PUT com o corpo cru (sem multipart), em memoria ou em fluxo. */
	public HttpResponse<String> putCru(String caminho, String contentType, HttpRequest.BodyPublisher corpo,
			String... headers) throws Exception {
		var req = HttpRequest.newBuilder(URI.create(base + caminho)).header("Content-Type", contentType).PUT(corpo);
		var xsrf = cookie("XSRF-TOKEN");
		if (xsrf != null) req.header("X-XSRF-TOKEN", xsrf);
		return enviar(req, headers);
	}

	/** PUT multipart com uma parte de arquivo; o Content-Type da parte e o declarado (pode mentir de proposito). */
	public HttpResponse<String> putArquivo(String caminho, String parte, String nomeDoArquivo, String contentType,
			byte[] conteudo, String... headers) throws Exception {
		var limite = "----clama" + UUID.randomUUID();
		var cab = ("--" + limite + "\r\nContent-Disposition: form-data; name=\"" + parte + "\"; filename=\""
				+ nomeDoArquivo + "\"\r\nContent-Type: " + contentType + "\r\n\r\n")
				.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		var fim = ("\r\n--" + limite + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
		var corpo = new byte[cab.length + conteudo.length + fim.length];
		System.arraycopy(cab, 0, corpo, 0, cab.length);
		System.arraycopy(conteudo, 0, corpo, cab.length, conteudo.length);
		System.arraycopy(fim, 0, corpo, cab.length + conteudo.length, fim.length);
		var req = HttpRequest.newBuilder(URI.create(base + caminho))
				.header("Content-Type", "multipart/form-data; boundary=" + limite)
				.PUT(HttpRequest.BodyPublishers.ofByteArray(corpo));
		var xsrf = cookie("XSRF-TOKEN");
		if (xsrf != null) req.header("X-XSRF-TOKEN", xsrf);
		return enviar(req, headers);
	}

	/** Fluxo do front: GET antes (emite o XSRF-TOKEN) e depois o POST de login. */
	public HttpResponse<String> login(String email, String senha, String... headers) throws Exception {
		if (cookie("XSRF-TOKEN") == null) get("/api/auth/eu");
		return post("/api/auth/login", JSON.writeValueAsString(java.util.Map.of("email", email, "senha", senha)),
				headers);
	}

	public String cookie(String nome) {
		return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals(nome))
				.map(c -> c.getValue()).findFirst().orElse(null);
	}

	private HttpResponse<String> enviar(HttpRequest.Builder req, String... headers) throws Exception {
		if (headers.length > 0) req.headers(headers);
		return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
	}

	public static JsonNode json(HttpResponse<String> res) {
		return JSON.readTree(res.body());
	}

	public static String emailUnico() {
		return "fulano-" + UUID.randomUUID() + "@exemplo.com";
	}

	public static Usuario usuario(UsuarioRepository repo, PasswordEncoder encoder, String email, String senha, Papel papel) {
		var u = Usuario.novo(email, "Fulana de Tal", papel, Instant.parse("2026-11-08T12:00:00Z"));
		if (senha != null) u.definirSenha(encoder.encode(senha));
		return repo.save(u);
	}
}
