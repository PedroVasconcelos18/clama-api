package me.clama.conta.web;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import me.clama.conta.Papel;
import me.clama.conta.Usuario;
import me.clama.conta.UsuarioRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** HttpClient real com cookie jar (o MockMvc nao reproduz cookie nem sessao); devolve o XSRF-TOKEN como a SPA. */
final class Navegador {

	static final JsonMapper JSON = JsonMapper.builder().build();

	private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
	private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();
	private final String base;

	Navegador(int porta) {
		base = "http://localhost:" + porta;
	}

	HttpResponse<String> get(String caminho, String... headers) throws Exception {
		return enviar(HttpRequest.newBuilder(URI.create(base + caminho)).GET(), headers);
	}

	HttpResponse<String> post(String caminho, String json, String... headers) throws Exception {
		return enviarJson("POST", caminho, json, headers);
	}

	HttpResponse<String> patch(String caminho, String json, String... headers) throws Exception {
		return enviarJson("PATCH", caminho, json, headers);
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

	/** Fluxo do front: GET antes (emite o XSRF-TOKEN) e depois o POST de login. */
	HttpResponse<String> login(String email, String senha, String... headers) throws Exception {
		if (cookie("XSRF-TOKEN") == null) get("/api/auth/eu");
		return post("/api/auth/login", JSON.writeValueAsString(java.util.Map.of("email", email, "senha", senha)),
				headers);
	}

	String cookie(String nome) {
		return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals(nome))
				.map(c -> c.getValue()).findFirst().orElse(null);
	}

	private HttpResponse<String> enviar(HttpRequest.Builder req, String... headers) throws Exception {
		if (headers.length > 0) req.headers(headers);
		return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
	}

	static JsonNode json(HttpResponse<String> res) {
		return JSON.readTree(res.body());
	}

	static String emailUnico() {
		return "fulano-" + UUID.randomUUID() + "@exemplo.com";
	}

	static Usuario usuario(UsuarioRepository repo, PasswordEncoder encoder, String email, String senha, Papel papel) {
		var u = Usuario.novo(email, "Fulana de Tal", papel, Instant.parse("2026-11-08T12:00:00Z"));
		if (senha != null) u.definirSenha(encoder.encode(senha));
		return repo.save(u);
	}
}
