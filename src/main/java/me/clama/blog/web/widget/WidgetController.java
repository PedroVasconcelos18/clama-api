package me.clama.blog.web.widget;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import me.clama.blog.interno.Comentarios;
import me.clama.blog.interno.Curtidas;
import me.clama.infra.seguranca.ClienteIp;
import me.clama.infra.web.ErroDeNegocio;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * As quatro rotas que o widget de engajamento do WordPress usa (mapa §1.2, §4.3), com barra final e nomes de campo
 * exatamente como o widget os le (snake_case). Fica fora do contrato OpenAPI: nao e API do front.
 */
@Hidden
@RestController
class WidgetController {

	private static final String SLUG = "{slug:[-a-zA-Z0-9_]{1,200}}";
	private static final int MAX_CORPO = 64 * 1024; // 2000 caracteres escapados em \\uXXXX cabem com folga
	private static final JsonMapper JSON = JsonMapper.builder().build();

	record Item(Long id, @JsonProperty("customer_nome") String customerNome, String conteudo,
			@JsonProperty("created_at") Instant createdAt) {
		static Item de(Comentarios.Publico c) {
			return new Item(c.id(), c.nome(), c.conteudo(), c.criadoEm());
		}
	}

	record Pagina(long count, List<Item> results) {}

	record Curtida(boolean liked, @JsonProperty("like_count") long likeCount) {}

	private final Comentarios comentarios;
	private final Curtidas curtidas;

	WidgetController(Comentarios comentarios, Curtidas curtidas) {
		this.comentarios = comentarios;
		this.curtidas = curtidas;
	}

	/** O token do cookie XSRF-TOKEN, para o widget mandar em {@code X-CSRFToken}. */
	@GetMapping("/api/csrf/")
	ResponseEntity<Map<String, String>> csrf(HttpServletRequest req) {
		var token = (CsrfToken) req.getAttribute(CsrfToken.class.getName());
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(Map.of("csrf_token", token.getToken()));
	}

	/** Cacheavel por 10 s (a cadeia de leitura nao poe cookie). Sempre 200: o widget so le {@code results}. */
	@GetMapping("/api/blog/posts/" + SLUG + "/comments/")
	ResponseEntity<Pagina> listar(@PathVariable String slug, HttpServletRequest req) {
		var lista = comentarios.listar(slug, ClienteIp.de(req));
		return ResponseEntity.ok().header("Cache-Control", "public, max-age=10")
				.body(new Pagina(lista.total(), lista.itens().stream().map(Item::de).toList()));
	}

	@PostMapping("/api/blog/posts/" + SLUG + "/comments/")
	ResponseEntity<Item> comentar(@PathVariable String slug, Authentication auth, HttpServletRequest req) {
		var usuario = Long.parseLong(auth.getName());
		comentarios.liberar(usuario); // ban e limite vem antes de olhar o corpo: JSON quebrado tambem gasta a janela
		var criado = comentarios.criar(usuario, slug, conteudoDo(req), ClienteIp.de(req));
		return ResponseEntity.status(HttpStatus.CREATED).body(Item.de(criado));
	}

	/** Alterna; o corpo (vazio) e ignorado. */
	@PostMapping("/api/blog/posts/" + SLUG + "/like/")
	Curtida curtir(@PathVariable String slug, Authentication auth) {
		var r = curtidas.alternar(Long.parseLong(auth.getName()), slug);
		return new Curtida(r.curtido(), r.total());
	}

	/** Le o corpo na mao, com teto, para o ban e o limite virem antes de qualquer erro de corpo. Ausente ou nao-texto = nulo. */
	private static String conteudoDo(HttpServletRequest req) {
		byte[] bruto;
		try {
			bruto = req.getInputStream().readNBytes(MAX_CORPO + 1);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		if (bruto.length > MAX_CORPO) throw new ErroDeNegocio(HttpStatus.BAD_REQUEST, "comentario_longo", "Corpo grande demais", Comentarios.LONGO);
		if (bruto.length == 0) return null;
		try {
			var campo = JSON.readTree(bruto).path("conteudo");
			return campo.isString() ? campo.asString() : null;
		} catch (JacksonException e) {
			throw new ErroDeNegocio(HttpStatus.BAD_REQUEST, "corpo_invalido", "Corpo da requisição inválido.", ErrosDoWidget.PASTORAL_GENERICA);
		}
	}
}
