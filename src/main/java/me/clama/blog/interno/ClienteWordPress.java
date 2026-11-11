package me.clama.blog.interno;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Leitura da API REST publica do WordPress (D5): sem credencial e sem retentativa. "Publicado" = veio na lista publica
 * e {@code content.protected} e falso. Qualquer falha (4xx, 5xx, rede, timeout, corpo que nao e JSON ou nao e lista) vira
 * {@link Indisponivel}: "nao consegui perguntar" nunca e "nao existe". As mensagens nunca levam o host.
 */
@Component
@EnableConfigurationProperties(BlogProps.class)
public class ClienteWordPress {

	static final int POR_PAGINA = 100;
	private static final String CAMPOS = "id,slug,title,link,date_gmt,content.protected";
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** Post como o WordPress o entrega; {@code protegido}: tem senha, entao nao esta publicado de fato. */
	public record PostWp(long id, String slug, String titulo, String url, Instant publicadoEm, boolean protegido) {
		public boolean publicado() { return !protegido; }
	}

	/** Uma pagina da lista; {@code totalPaginas} vem do header {@code X-WP-TotalPages} (ausente = 1). */
	public record Pagina(List<PostWp> posts, int totalPaginas) {}

	/** O WordPress nao respondeu como esperado. */
	public static class Indisponivel extends RuntimeException {
		Indisponivel(String mensagem) { super(mensagem); }
	}

	@HttpExchange("/wp-json/wp/v2/posts")
	interface Cliente {
		@GetExchange
		ResponseEntity<String> porSlug(@RequestParam String slug, @RequestParam("_fields") String campos);

		@GetExchange
		ResponseEntity<String> pagina(@RequestParam int page, @RequestParam("per_page") int porPagina,
				@RequestParam("_fields") String campos);
	}

	private final Cliente cliente;

	@Autowired
	ClienteWordPress(BlogProps props) {
		this(props.wordpressUrl(), props.timeout());
	}

	ClienteWordPress(String url, Duration timeout) {
		if (url == null || url.isBlank()) {
			this.cliente = null;
			return;
		}
		var fabrica = new SimpleClientHttpRequestFactory();
		fabrica.setConnectTimeout(timeout);
		fabrica.setReadTimeout(timeout);
		var rest = RestClient.builder().baseUrl(url.strip().replaceAll("/+$", "")).requestFactory(fabrica).build();
		this.cliente = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rest)).build().createClient(Cliente.class);
	}

	/** Os posts publicos com esse slug (normalmente 0 ou 1). */
	public List<PostWp> buscarPorSlug(String slug) {
		return ler(() -> cliente.porSlug(slug, CAMPOS)).posts();
	}

	/** Pagina {@code n} (a partir de 1) da lista publica, {@value #POR_PAGINA} por pagina. */
	public Pagina listar(int n) {
		return ler(() -> cliente.pagina(n, POR_PAGINA, CAMPOS));
	}

	private Pagina ler(java.util.function.Supplier<ResponseEntity<String>> chamada) {
		if (cliente == null) throw new Indisponivel("WordPress nao configurado");
		ResponseEntity<String> resposta;
		try {
			resposta = chamada.get();
		} catch (RuntimeException e) {
			// a mensagem da excecao leva a URL (com o host): so o tipo e o status saem
			var status = e instanceof org.springframework.web.client.RestClientResponseException r ? " (HTTP " + r.getStatusCode().value() + ")" : "";
			throw new Indisponivel("WordPress indisponivel: " + e.getClass().getSimpleName() + status);
		}
		try {
			var raiz = JSON.readTree(resposta.getBody() == null ? "" : resposta.getBody());
			if (raiz == null || !raiz.isArray()) throw new Indisponivel("WordPress respondeu algo que nao e uma lista");
			var posts = new ArrayList<PostWp>();
			for (var item : raiz) posts.add(post(item));
			return new Pagina(posts, totalDePaginas(resposta));
		} catch (tools.jackson.core.JacksonException e) {
			throw new Indisponivel("WordPress respondeu algo que nao e JSON");
		}
	}

	private static int totalDePaginas(ResponseEntity<String> resposta) {
		var h = resposta.getHeaders().getFirst("X-WP-TotalPages");
		try {
			return h == null ? 1 : Math.max(1, Integer.parseInt(h.strip()));
		} catch (NumberFormatException e) {
			return 1;
		}
	}

	private static PostWp post(JsonNode n) {
		var id = n.path("id").asLong(0);
		var slug = n.path("slug").asString("");
		if (id <= 0 || slug.isEmpty()) throw new Indisponivel("WordPress devolveu um post sem id ou slug");
		var titulo = HtmlUtils.htmlUnescape(n.path("title").path("rendered").asString(""));
		return new PostWp(id, cortar(slug, 200), cortar(titulo, 200), cortar(n.path("link").asString(""), 500),
				data(n.path("date_gmt").asString("")), n.path("content").path("protected").asBoolean(false));
	}

	/** {@code date_gmt} vem sem fuso ("2026-11-01T12:00:00"); rascunho ou lixo = sem data. */
	private static Instant data(String s) {
		try {
			return s.isEmpty() ? null : LocalDateTime.parse(s).toInstant(ZoneOffset.UTC);
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	/** Corta por code points, como o varchar do banco. */
	static String cortar(String s, int n) {
		return s.codePointCount(0, s.length()) <= n ? s : s.substring(0, s.offsetByCodePoints(0, n));
	}
}
