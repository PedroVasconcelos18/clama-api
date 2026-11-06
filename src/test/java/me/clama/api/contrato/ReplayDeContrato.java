package me.clama.api.contrato;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import me.clama.api.BancoDeTeste;
import me.clama.api.comum.cripto.CampoCriptografado;
import me.clama.api.comum.ratelimit.RateLimiter;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.senha.SenhaDjango;
import me.clama.api.identidade.token.ServicoDeTokens;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reexecuta um cenario gravado do Django (clama-backend/contrato/test_gravar_*.py) contra o Java e compara
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
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final String SQL_ESTADO = "SELECT p.*, u.email AS user_email FROM orders_pedido p"
            + " LEFT JOIN users_user u ON u.id = p.user_id ORDER BY p.created_at, p.id";
    private static final String FRONTEIRA = "ContratoClamaFronteira";
    private static final Pattern SET_COOKIE = Pattern.compile("\\{set_cookie:(\\d+):(.+)\\}", COMO_PYTHON);

    private final JdbcTemplate jdbc;
    private final UsuarioRepository usuarios;
    private final ServicoDeTokens tokens;
    private final RateLimiter limiter;
    private final List<String> tabelas;
    private final SenhaDjango senhas = new SenhaDjango();
    private final HttpClient http = HttpClient.newHttpClient();
    private StubDeIntegracoes stub;
    private Environment ambiente;

    /** tabelas: as que o cenario toca, truncadas (CASCADE) antes de cada execucao, como o User.objects.all().delete(). */
    public ReplayDeContrato(JdbcTemplate jdbc, UsuarioRepository usuarios, ServicoDeTokens tokens, RateLimiter limiter,
            List<String> tabelas) {
        this.jdbc = jdbc;
        this.usuarios = usuarios;
        this.tokens = tokens;
        this.limiter = limiter;
        this.tabelas = tabelas;
    }

    /**
     * Liga o stub de integracoes: o cenario serve as respostas "mercadopago" e, no fim, as requisicoes recebidas tem
     * de bater com "mercadopago_requisicoes" (nenhuma, se o cenario nao declarar). "configuracao" (o override_settings
     * do gravador) tem de bater com o ambiente do contexto Spring.
     */
    public ReplayDeContrato comStub(StubDeIntegracoes stub, Environment ambiente) {
        this.stub = stub;
        this.ambiente = ambiente;
        return this;
    }

    public void executar(Path cenarioJson, URI base) throws Exception {
        JsonNode cenario = JSON.readTree(cenarioJson.toFile());
        Set<String> fixos = uuidsFixos(cenario);
        String arquivo = cenarioJson.getFileName().toString();
        if (stub == null) {
            assertThat(cenario.has("mercadopago") || cenario.has("configuracao"))
                    .as("%s declara integracoes: use comStub", arquivo).isFalse();
        } else {
            for (Map.Entry<String, JsonNode> c : cenario.path("configuracao").properties()) {
                assertThat(ambiente.getProperty(c.getKey())).as("%s: rode num contexto com %s=%s", arquivo, c.getKey(),
                        c.getValue().asString()).isEqualTo(c.getValue().asString());
            }
            stub.preparar(cenario.path("mercadopago"));
        }
        BancoDeTeste.exigirContainer(jdbc); // o TRUNCATE ... CASCADE apagaria pedidos, blog e tokens de um banco real
        jdbc.execute("TRUNCATE " + String.join(", ", tabelas) + " RESTART IDENTITY CASCADE");
        limiter.limpar(); // cache.clear() do gravador
        for (JsonNode u : cenario.path("usuarios")) {
            criarUsuario(u);
        }
        for (Map.Entry<String, JsonNode> tabela : cenario.path("dados").properties()) {
            for (JsonNode linha : tabela.getValue()) {
                inserir(tabela.getKey(), linha);
            }
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
            byte[] corpo = null;
            String tipo = null;
            if (req.has("body")) {
                corpo = JSON.writeValueAsBytes(req.get("body"));
                tipo = "application/json";
            } else if (req.has("multipart")) {
                corpo = multipart(req.get("multipart"));
                tipo = "multipart/form-data; boundary=" + FRONTEIRA;
            }
            HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve(req.get("path").asString()))
                    .method(metodo, corpo == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(corpo));
            b.header("Host", "testserver"); // SERVER_NAME do test client: URL absoluta gravada como http://testserver/
            if (tipo != null) {
                b.header("Content-Type", tipo);
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
            // HTML (ex.: o 404 do resolver de URL do Django) e comparado como texto, como o gravador grava
            JsonNode corpoResp = resp.body().isEmpty() ? NullNode.getInstance()
                    : resp.headers().firstValue("Content-Type").orElse("").contains("json") ? JSON.readTree(resp.body())
                    : NOS.stringNode(resp.body());
            if (corpoResp.isObject() && corpoResp.has("csrf_token")) {
                ctx.csrf = corpoResp.get("csrf_token").asString();
            }
            Map<String, String> valores = new LinkedHashMap<>();
            recebidos.forEach((nome, atributos) -> valores.put(nome, atributos.get("")));
            ctx.setCookies.add(valores);

            ObjectNode obtido = NOS.objectNode();
            obtido.put("status", resp.statusCode());
            obtido.set("body", normalizar(corpoResp, null, fixos));
            ArrayNode lista = obtido.putArray("cookies");
            recebidos.forEach((nome, a) -> lista.add(cookieNormalizado(nome, a)));
            assertThat(obtido).as("%s passo %d: %s %s", cenarioJson.getFileName(), i, metodo, req.get("path"))
                    .isEqualTo(passo.get("response"));
            i++;
        }
        if (stub != null) {
            stub.conferir(cenario.path("mercadopago_requisicoes"), arquivo);
        }
        if (cenario.has("estado_final")) {
            conferirEstadoFinal(cenario.get("estado_final").get("orders_pedido"), fixos, arquivo);
        }
    }

    /**
     * _estado_final do gravador: todas as linhas de orders_pedido depois do ultimo passo, nas colunas que o JSON lista,
     * com as cifradas decifradas (CampoCriptografado) e normalizadas como as respostas. Numero compara por valor.
     */
    private void conferirEstadoFinal(JsonNode tabela, Set<String> fixos, String arquivo) {
        List<String> colunas = new ArrayList<>();
        tabela.get("colunas").forEach(c -> colunas.add(c.asString()));
        Set<String> cifradas = new HashSet<>();
        tabela.get("cifradas_decifradas").forEach(c -> cifradas.add(c.asString()));
        CampoCriptografado campo = new CampoCriptografado();
        ArrayNode linhas = NOS.arrayNode();
        jdbc.query(SQL_ESTADO, (RowCallbackHandler) rs -> {
            ObjectNode l = linhas.addObject();
            for (String c : colunas) {
                Object v = rs.getObject(c);
                if (v == null) {
                    l.putNull(c);
                } else if (cifradas.contains(c)) {
                    l.put(c, campo.convertToEntityAttribute(rs.getString(c)));
                } else if (v instanceof Boolean b) {
                    l.put(c, b);
                } else if (v instanceof Number n) {
                    l.put(c, n.longValue());
                } else {
                    l.put(c, rs.getString(c)); // timestamptz, uuid, inet como texto do Postgres
                }
            }
        });
        assertThat(StubDeIntegracoes.numerosPorValor(normalizar(linhas, null, fixos)))
                .as("%s: estado final de orders_pedido", arquivo)
                .isEqualTo(StubDeIntegracoes.numerosPorValor(tabela.get("linhas")));
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

    /**
     * Linha de "dados" (o Model.objects.create do gravador). Valores vao como texto de tipo nao especificado e o
     * Postgres converte pela coluna (uuid, jsonb, timestamptz, inet...). Sem created_at na linha, created_at/updated_at
     * sao o now() da insercao, como o auto_now do TimestampedModel; clock_timestamp() mantem a ordem de insercao. Com
     * created_at (linhas de pedido, gravadas como ficaram no banco, com os tokens Fernet), a linha vai tal qual.
     */
    private void inserir(String tabela, JsonNode linha) {
        List<String> colunas = new ArrayList<>();
        List<String> valores = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : linha.properties()) {
            colunas.add(e.getKey());
            JsonNode v = e.getValue();
            valores.add(v.isNull() ? null : v.isString() ? v.asString() : v.toString());
        }
        boolean datas = !linha.has("created_at");
        String sql = "INSERT INTO " + tabela + " (" + String.join(", ", colunas) + (datas ? ", created_at, updated_at" : "")
                + ") VALUES (" + String.join(", ", Collections.nCopies(colunas.size(), "?"))
                + (datas ? ", clock_timestamp(), clock_timestamp()" : "") + ")";
        jdbc.update(c -> {
            PreparedStatement ps = c.prepareStatement(sql);
            for (int k = 0; k < valores.size(); k++) {
                ps.setObject(k + 1, valores.get(k), Types.OTHER);
            }
            return ps;
        });
    }

    /** _multipart do gravador: {"campos": {...}, "arquivos": [{campo, nome, content_type, conteudo_base64}]}. */
    private static byte[] multipart(JsonNode m) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, JsonNode> c : m.path("campos").properties()) {
            out.writeBytes(("--" + FRONTEIRA + "\r\nContent-Disposition: form-data; name=\"" + c.getKey() + "\"\r\n\r\n"
                    + c.getValue().asString() + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        for (JsonNode a : m.path("arquivos")) {
            out.writeBytes(("--" + FRONTEIRA + "\r\nContent-Disposition: form-data; name=\"" + a.get("campo").asString()
                    + "\"; filename=\"" + a.get("nome").asString() + "\"\r\nContent-Type: "
                    + a.get("content_type").asString() + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.writeBytes(Base64.getDecoder().decode(a.get("conteudo_base64").asString()));
            out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.writeBytes(("--" + FRONTEIRA + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
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

    /** uuids_fixos(dados, passos) do gravador: os UUIDs das linhas de dados e dos requests. */
    static Set<String> uuidsFixos(JsonNode cenario) {
        StringBuilder texto = new StringBuilder(cenario.path("dados").toString());
        cenario.path("passos").forEach(p -> texto.append(p.path("request")));
        return UUID.matcher(texto).results().map(r -> r.group()).collect(Collectors.toSet());
    }

    static JsonNode normalizar(JsonNode valor, String chave) {
        return normalizar(valor, chave, Set.of());
    }

    /**
     * normalizar() do normalizar.py: o regex de timestamp casa prefixo (re.match), nao ISO estrito. UUID fixo do
     * cenario fica como esta; so o gerado vira <uuid>.
     */
    static JsonNode normalizar(JsonNode valor, String chave, Set<String> fixos) {
        if (valor.isObject()) {
            ObjectNode o = NOS.objectNode();
            for (Map.Entry<String, JsonNode> e : valor.properties()) {
                o.set(e.getKey(), normalizar(e.getValue(), e.getKey(), fixos));
            }
            return o;
        }
        if (valor.isArray()) {
            ArrayNode a = NOS.arrayNode();
            valor.forEach(v -> a.add(normalizar(v, null, fixos)));
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
            return NOS.stringNode(UUID.matcher(valor.asString())
                    .replaceAll(m -> fixos.contains(m.group()) ? m.group() : "<uuid>"));
        }
        return valor;
    }
}
