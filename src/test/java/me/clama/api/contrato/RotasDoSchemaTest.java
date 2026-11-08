package me.clama.api.contrato;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import me.clama.api.BancoDeTeste;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Completude de rotas contra o schema do Django (mapa da etapa 9, §3): no lugar de um /api/schema/ no Java, cada
 * (caminho, metodo) do drf-spectacular precisa ter handler no Spring ou entrar em REMOVIDAS com motivo; cada rota do
 * Java que o Django nao tem precisa entrar em NOVAS com motivo. O schema (src/test/resources/schema-django.json) foi
 * gerado do clama-backend 564daea:
 *
 * <pre>
 * DJANGO_SETTINGS_MODULE=config.settings.test FIELD_ENCRYPTION_KEY=&lt;qualquer Fernet&gt; DATABASE_URL=postgres://x:x@localhost:1/x \
 *   .venv/bin/python manage.py spectacular --format openapi-json --file schema-django.json
 * </pre>
 *
 * Normalizacao: toda variavel de caminho ({id}, {uuid}, {x:regex}) e todo segmento "*" viram {}; a barra final fica.
 * HEAD e OPTIONS nao contam, dos dois lados: o Django os acrescenta sozinho e o spectacular nao os lista; no Spring
 * o HEAD vem do GET e o OPTIONS e implicito. Um handler sem restricao de metodo conta para qualquer metodo.
 * O schema tem SERVE_INCLUDE_SCHEMA=False, entao /api/schema/, /api/docs/ e /api/redoc/ nao aparecem nele: ficam em
 * REMOVIDAS so para documentar, e o teste exige que o Java realmente nao as tenha.
 */
@SpringBootTest(properties = {"ANTHROPIC_API_KEY=", "TURNSTILE_SECRET_KEY="})
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class RotasDoSchemaTest {
	/** Rotas do Django que o Java nao tem de proposito. Chave: "METODO caminho" (ou "* caminho" para todos). */
	static final Map<String, String> REMOVIDAS = new LinkedHashMap<>();
	/** Rotas do Java que o Django nao tem. Mesma chave. */
	static final Map<String, String> NOVAS = new LinkedHashMap<>();

	static {
		String post = "Modelo Post nao migra (spec 2026-11-05, 'O modelo Post e tudo que so existe por causa dele'; mapa da etapa 6 §1, R1): "
				+ "o blog vive no WordPress e o Java so serve comentarios/curtidas pelo espelho";
		for (String r : new String[]{"GET /api/blog/posts/", "POST /api/blog/posts/", "GET /api/blog/posts/{}/",
				"PUT /api/blog/posts/{}/", "PATCH /api/blog/posts/{}/", "DELETE /api/blog/posts/{}/",
				"POST /api/blog/posts/{}/publicar/", "POST /api/blog/posts/{}/despublicar/"}) {
			REMOVIDAS.put(r, post + " (CRUD do Post e publicar/despublicar)");
		}
		for (String r : new String[]{"GET /api/blog/public/posts/", "GET /api/blog/public/posts/{}/"}) {
			REMOVIDAS.put(r, post + " (listagem publica do Post, usada so pelo SSG antigo)");
		}
		REMOVIDAS.put("POST /api/webhooks/zapi/", "Z-API (WhatsApp) nao migra: decisao do Pedro em 2026-11-05, nao e usado hoje "
				+ "(spec 2026-11-05, linha 'Z-API nao migra'); ZAPI_* nem esta definido em producao");
		String doc = "Decisao da etapa 9 (mapa §3, item 49): sem endpoint de schema, este teste faz o papel dele; "
				+ "PENDENTE de confirmacao do Pedro. O drf-spectacular nao lista a propria rota (SERVE_INCLUDE_SCHEMA=False), "
				+ "entao a entrada so documenta e o teste garante que o Java nao a tem";
		for (String r : new String[]{"GET /api/schema/", "GET /api/docs/", "GET /api/redoc/"}) REMOVIDAS.put(r, doc);
		REMOVIDAS.put("GET /sitemap.xml", post + " (sitemap do Post; fora do schema, view comum do Django)");
		REMOVIDAS.put("GET /robots.txt", post + " (robots.txt do blog, com o sitemap do Post; fora do schema)");
		REMOVIDAS.put("GET /api/_sentry-debug/", "So existe no Django com DEBUG (config/urls.py); fora do schema e sem uso em producao");

		String e7 = "Etapa 7 (mapa 2026-11-07-mapa-etapa-7-admin): o Django so tinha o /admin em HTML (sessao), sem API; "
				+ "o painel React ganhou a API nova no Java";
		NOVAS.put("GET /api/admin/repasses/", e7 + ", §1: lista de repasses (instituicoes/admin.py)");
		NOVAS.put("GET /api/admin/repasses/{}/", e7 + ", §1: detalhe de um repasse");
		NOVAS.put("GET /api/admin/webhooks/", e7 + ", §2: webhooks recebidos (WebhookEvento)");
		NOVAS.put("GET /api/admin/webhooks/{}/", e7 + ", §2: detalhe do webhook recebido");
		NOVAS.put("POST /api/admin/webhooks/{}/reprocessar/", e7 + ", §2.2: reprocessar um webhook (no Django editar o status nao fazia nada)");
		NOVAS.put("GET /api/admin/freemium/bloqueios/", e7 + ", §3: lista do FreemiumBlacklist");
		NOVAS.put("POST /api/admin/freemium/bloqueios/buscar/", e7 + ", §3: busca em claro nos campos cifrados (POST para nao por PII na URL)");
		NOVAS.put("DELETE /api/admin/freemium/bloqueios/{}/", e7 + ", §3.2: desbloquear, com auditoria");
		NOVAS.put("GET /api/admin/freemium/tokens/", e7 + ", §3: FreemiumConfirmationToken, so leitura");
		NOVAS.put("POST /api/admin/customers/", e7 + ", §4: criar usuario admin (era create_clama_admin / UserAdmin)");
		NOVAS.put("PATCH /api/admin/customers/{}/", e7 + ", §4: ligar/desligar is_clama_admin e is_active");
		NOVAS.put("POST /api/admin/customers/{}/senha/", e7 + ", §4: definir senha de um usuario (era o UserAdmin)");
		NOVAS.put("DELETE /api/admin/customers/{}/", e7 + ", §4.3: excluir usuario pelo servico de exclusao compartilhado");
		NOVAS.put("PATCH /api/admin/pedidos/{}/", e7 + ", §7: edicoes de pedido que o PedidoAdmin permitia");
	}

	/** Rotas que o Django serve por view comum (fora do DRF) e portanto nao aparecem no schema, mas o Java mantem. */
	static final Map<String, String> FORA_DO_SCHEMA = Map.of("POST /api/webhooks/wordpress/",
			"WordPressWebhookView e uma view comum do Django (clama/blog/urls.py), que o spectacular nao lista; o Java migrou (mapa da etapa 6)");

	@Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mapeamento;

	@Test
	void rotasDoJavaCobremOSchemaDoDjango() throws Exception {
		Set<String> django = rotasDoDjango();
		django.addAll(FORA_DO_SCHEMA.keySet());
		Set<String> java = rotasDoJava();

		List<String> faltam = new ArrayList<>();
		for (String r : django) {
			if (!tem(java, r) && !REMOVIDAS.containsKey(r)) faltam.add(r);
		}
		List<String> semExplicacao = new ArrayList<>();
		for (String r : java) {
			if (!temNo(django, r) && !NOVAS.containsKey(r)) semExplicacao.add(r);
		}
		// listas velhas: o motivo some quando a rota deixa de existir de verdade
		List<String> removidasVivas = REMOVIDAS.keySet().stream().filter(r -> tem(java, r)).toList();
		List<String> novasMortas = NOVAS.keySet().stream().filter(r -> !java.contains(r) || temNo(django, r)).toList();

		assertThat(faltam).as("no schema do Django, sem handler no Java e fora de REMOVIDAS").isEmpty();
		assertThat(semExplicacao).as("no Java, fora do schema do Django e fora de NOVAS").isEmpty();
		assertThat(removidasVivas).as("em REMOVIDAS mas o Java tem handler").isEmpty();
		assertThat(novasMortas).as("em NOVAS mas o Java nao tem a rota ou o Django tambem tem").isEmpty();
	}

	/** Chave "METODO caminho"; java guarda "* caminho" para handler sem restricao de metodo. */
	static boolean tem(Set<String> java, String django) {
		String[] p = django.split(" ", 2);
		return java.contains(django) || java.contains("* " + p[1]);
	}

	static boolean temNo(Set<String> django, String java) {
		String[] p = java.split(" ", 2);
		if (p[0].equals("*")) return django.stream().anyMatch(d -> d.endsWith(" " + p[1]));
		return django.contains(java);
	}

	Set<String> rotasDoJava() {
		Set<String> r = new TreeSet<>();
		for (RequestMappingInfo info : mapeamento.getHandlerMethods().keySet()) {
			var padroes = info.getPathPatternsCondition().getPatternValues();
			var metodos = info.getMethodsCondition().getMethods().stream().filter(m -> m != RequestMethod.HEAD
					&& m != RequestMethod.OPTIONS).map(Enum::name).collect(Collectors.toCollection(TreeSet::new));
			if (metodos.isEmpty()) metodos.add("*");
			for (String padrao : padroes) {
				for (String m : metodos) r.add(m + " " + normalizar(padrao));
			}
		}
		r.removeIf(k -> k.endsWith(" /error")); // BasicErrorController
		return r;
	}

	static Set<String> rotasDoDjango() throws Exception {
		try (InputStream in = RotasDoSchemaTest.class.getResourceAsStream("/schema-django.json")) {
			JsonNode paths = new ObjectMapper().readTree(in).get("paths");
			Set<String> r = new TreeSet<>();
			for (var e : paths.properties()) {
				for (var m : e.getValue().propertyNames()) {
					if (m.equals("parameters") || m.equals("head") || m.equals("options")) continue;
					r.add(m.toUpperCase() + " " + normalizar(e.getKey()));
				}
			}
			return r;
		}
	}

	/** Variavel de caminho vira {}; conta chaves para aguentar regex com quantificador, como {id:[0-9a-f]{8}}. */
	static String normalizar(String caminho) {
		StringBuilder sb = new StringBuilder();
		int nivel = 0;
		for (char c : caminho.toCharArray()) {
			if (c == '{' && nivel++ == 0) sb.append("{}");
			else if (c == '}') nivel--;
			else if (nivel == 0) sb.append(c);
		}
		return sb.toString().replaceAll("(?<=/)\\*(?=/|$)", "{}");
	}
}
