package me.clama.api.contrato;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import me.clama.api.BancoDeTeste;
import me.clama.api.comum.ratelimit.RateLimiter;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.senha.SenhaDjango;
import me.clama.api.identidade.token.ServicoDeTokens;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reexecuta um cenario gravado do Django (clama-backend/contrato/test_gravar_identidade.py) contra o Java e compara
 * status, corpo e cookies, normalizados com as regras do contrato/normalizar.py. O jar imita o do test client do
 * Django: cookie apagado fica com valor "" e continua sendo enviado.
 */
public class ReplayDeContrato {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNodeFactory NOS = JsonNodeFactory.instance;
    // \w e \d do Python 3 sao unicode; $ do Python so aceita "\n" antes do fim (UNIX_LINES)
    private static final int COMO_PYTHON = Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNIX_LINES;
    private static final Pattern JWT = Pattern.compile("^eyJ[\\w-]*\\.[\\w-]+\\.[\\w-]*$", COMO_PYTHON);
    private static final Pattern TS = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}", COMO_PYTHON);
    private static final Pattern REFRESH = Pattern.compile("\\{refresh:(.+)\\}", COMO_PYTHON);
    private static final Pattern SET_COOKIE = Pattern.compile("\\{set_cookie:(\\d+):(.+)\\}", COMO_PYTHON);

    private final JdbcTemplate jdbc;
    private final UsuarioRepository usuarios;
    private final ServicoDeTokens tokens;
    private final RateLimiter limiter;
    private final List<String> tabelas;
    private final SenhaDjango senhas = new SenhaDjango();
    private final HttpClient http = HttpClient.newHttpClient();

    /** tabelas: as que o cenario toca, truncadas (CASCADE) antes de cada execucao, como o User.objects.all().delete(). */
    public ReplayDeContrato(JdbcTemplate jdbc, UsuarioRepository usuarios, ServicoDeTokens tokens, RateLimiter limiter,
            List<String> tabelas) {
        this.jdbc = jdbc;
        this.usuarios = usuarios;
        this.tokens = tokens;
        this.limiter = limiter;
        this.tabelas = tabelas;
    }

    public void executar(Path cenarioJson, URI base) throws Exception {
        JsonNode cenario = JSON.readTree(cenarioJson.toFile());
        BancoDeTeste.exigirContainer(jdbc); // o TRUNCATE ... CASCADE apagaria pedidos, blog e tokens de um banco real
        jdbc.execute("TRUNCATE " + String.join(", ", tabelas) + " RESTART IDENTITY CASCADE");
        limiter.limpar(); // cache.clear() do gravador
        for (JsonNode u : cenario.path("usuarios")) {
            criarUsuario(u);
        }

        Map<String, String> jar = new LinkedHashMap<>();
        Contexto ctx = new Contexto();
        int i = 0;
        for (JsonNode passo : cenario.get("passos")) {
            JsonNode req = passo.get("request");
            // cookies_extras valem so pra este passo; o jar volta ao que era depois
            Map<String, String> antes = new HashMap<>();
            for (Map.Entry<String, JsonNode> e : req.path("cookies_extras").properties()) {
                antes.put(e.getKey(), jar.get(e.getKey()));
                jar.put(e.getKey(), resolver(e.getValue().asString(), ctx));
            }

            String metodo = req.get("method").asString();
            String corpo = req.has("body") ? JSON.writeValueAsString(req.get("body")) : null;
            HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve(req.get("path").asString()))
                    .method(metodo, corpo == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(corpo));
            if (corpo != null) {
                b.header("Content-Type", "application/json");
            }
            for (Map.Entry<String, JsonNode> h : req.path("headers").properties()) {
                b.header(h.getKey(), resolver(h.getValue().asString(), ctx));
            }
            if (!jar.isEmpty()) {
                b.header("Cookie", jar.entrySet().stream().map(c -> c.getKey() + "=" + c.getValue()).sorted()
                        .collect(Collectors.joining("; ")));
            }
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());

            Map<String, Map<String, String>> recebidos = cookies(resp);
            recebidos.forEach((nome, atributos) -> jar.put(nome, atributos.get("")));
            antes.forEach((nome, valor) -> {
                if (recebidos.containsKey(nome)) {
                    return;
                }
                if (valor == null) {
                    jar.remove(nome);
                } else {
                    jar.put(nome, valor);
                }
            });
            JsonNode corpoResp = resp.body().isEmpty() ? NullNode.getInstance() : JSON.readTree(resp.body());
            if (corpoResp.isObject() && corpoResp.has("csrf_token")) {
                ctx.csrf = corpoResp.get("csrf_token").asString();
            }
            Map<String, String> valores = new LinkedHashMap<>();
            recebidos.forEach((nome, atributos) -> valores.put(nome, atributos.get("")));
            ctx.setCookies.add(valores);

            ObjectNode obtido = NOS.objectNode();
            obtido.put("status", resp.statusCode());
            obtido.set("body", normalizar(corpoResp, null));
            ArrayNode lista = obtido.putArray("cookies");
            recebidos.forEach((nome, a) -> lista.add(cookieNormalizado(nome, a)));
            assertThat(obtido).as("%s passo %d: %s %s", cenarioJson.getFileName(), i, metodo, req.get("path"))
                    .isEqualTo(passo.get("response"));
            i++;
        }
    }

    private static final class Contexto {
        String csrf;
        final List<Map<String, String>> setCookies = new ArrayList<>();
    }

    private void criarUsuario(JsonNode d) {
        Usuario u = new Usuario();
        u.setEmail(d.get("email").asString());
        u.setPassword(senhas.encode(d.get("senha").asString()));
        u.setNomeCompleto(d.get("nome_completo").asString());
        u.setClamaAdmin(d.get("is_clama_admin").asBoolean());
        u.setActive(d.get("is_active").asBoolean());
        u.setForceChangePassword(d.get("force_change_password").asBoolean());
        u.setCpfCnpj(d.get("cpf_cnpj").isNull() ? null : d.get("cpf_cnpj").asString());
        u.setTelefone(d.get("telefone").isNull() ? null : d.get("telefone").asString());
        usuarios.saveAndFlush(u);
    }

    /** _resolver do gravador: {csrf}, {refresh:<email>}, {set_cookie:<passo>:<nome>}. */
    private String resolver(String valor, Contexto ctx) {
        if (valor.equals("{csrf}")) {
            return ctx.csrf;
        }
        Matcher m = REFRESH.matcher(valor);
        if (m.matches()) {
            return tokens.emitirPar(usuarios.buscarPorEmailIgnorandoCaixa(m.group(1)).getFirst()).refresh();
        }
        m = SET_COOKIE.matcher(valor);
        if (m.matches()) {
            return ctx.setCookies.get(Integer.parseInt(m.group(1))).get(m.group(2));
        }
        return valor;
    }

    /** Set-Cookie por nome (o ultimo vence, como no SimpleCookie); "" guarda o valor, o resto sao atributos. */
    private static Map<String, Map<String, String>> cookies(HttpResponse<?> resp) {
        Map<String, Map<String, String>> saida = new LinkedHashMap<>();
        for (String header : resp.headers().allValues("Set-Cookie")) {
            String[] partes = header.split(";");
            String[] nv = partes[0].split("=", 2);
            Map<String, String> a = new HashMap<>();
            a.put("", nv.length > 1 ? nv[1].trim() : "");
            for (int k = 1; k < partes.length; k++) {
                String[] kv = partes[k].split("=", 2);
                a.put(kv[0].trim().toLowerCase(Locale.ROOT), kv.length > 1 ? kv[1].trim() : "");
            }
            saida.put(nv[0].trim(), a);
        }
        return saida;
    }

    /** _cookies_resposta do gravador + normalizar_cookie. */
    private static ObjectNode cookieNormalizado(String nome, Map<String, String> a) {
        String valor = a.get("");
        Integer maxAge = a.containsKey("max-age") ? Integer.valueOf(a.get("max-age")) : null;
        boolean apagado = Integer.valueOf(0).equals(maxAge) || valor.isEmpty();
        String normalizado = apagado ? "" : nome.equals("csrftoken") ? "<csrf>" : JWT.matcher(valor).lookingAt() ? "<jwt>" : valor;
        ObjectNode c = NOS.objectNode();
        c.put("name", nome);
        c.put("value", normalizado);
        c.put("path", a.getOrDefault("path", ""));
        c.put("httponly", a.containsKey("httponly"));
        c.put("samesite", a.getOrDefault("samesite", ""));
        if (maxAge == null) {
            c.putNull("max_age");
        } else {
            c.put("max_age", maxAge);
        }
        return c;
    }

    /** normalizar() do normalizar.py: o regex de timestamp casa prefixo (re.match), nao ISO estrito. */
    static JsonNode normalizar(JsonNode valor, String chave) {
        if (valor.isObject()) {
            ObjectNode o = NOS.objectNode();
            for (Map.Entry<String, JsonNode> e : valor.properties()) {
                o.set(e.getKey(), normalizar(e.getValue(), e.getKey()));
            }
            return o;
        }
        if (valor.isArray()) {
            ArrayNode a = NOS.arrayNode();
            valor.forEach(v -> a.add(normalizar(v, null)));
            return a;
        }
        if ("csrf_token".equals(chave) && valor.isString()) {
            return NOS.stringNode("<csrf>");
        }
        if ("id".equals(chave) && valor.isIntegralNumber()) {
            return NOS.stringNode("<id>");
        }
        if (valor.isString()) {
            if (JWT.matcher(valor.asString()).lookingAt()) {
                return NOS.stringNode("<jwt>");
            }
            if (TS.matcher(valor.asString()).lookingAt()) {
                return NOS.stringNode("<ts>");
            }
        }
        return valor;
    }
}
