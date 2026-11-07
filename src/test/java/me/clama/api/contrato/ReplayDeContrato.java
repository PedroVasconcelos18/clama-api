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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import me.clama.api.BancoDeTeste;
import me.clama.api.comum.cripto.CampoCriptografado;
import me.clama.api.comum.email.CaixaDeSaidaEmMemoria;
import me.clama.api.comum.email.Email;
import me.clama.api.comum.email.ResendEnviador;
import me.clama.api.comum.fila.TipoDeJob;
import me.clama.api.comum.ratelimit.RateLimiter;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.senha.SenhaDjango;
import me.clama.api.identidade.token.ServicoDeTokens;
import org.postgresql.util.PGobject;
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
    private static final Pattern UUID_TEXTO = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    /** As SQL do estado final, iguais as do gravador (TABELAS em test_gravar_pagamento_oracao.py). */
    private static final Map<String, String> SQL_ESTADO = Map.of(
            "orders_pedido", "SELECT p.*, u.email AS user_email FROM orders_pedido p"
                    + " LEFT JOIN users_user u ON u.id = p.user_id ORDER BY p.created_at, p.id",
            "users_user", "SELECT u.*, split_part(u.password, '$', 1) AS password_algoritmo FROM users_user u ORDER BY u.id",
            "instituicoes_repasse", "SELECT * FROM instituicoes_repasse ORDER BY created_at, id",
            "payments_webhookevento", "SELECT * FROM payments_webhookevento ORDER BY created_at, id");
    /** Integracoes HTTP simuladas: o cenario traz "<nome>" (respostas) e "<nome>_requisicoes" (o que o Django mandou). */
    private static final List<String> INTEGRACOES = List.of("mercadopago", "anthropic");
    private static final List<String> CHAVES_DE_ORACAO = List.of("job", "emails", "jobs_enfileirados",
            "senhas_temporarias", "senhas_temporarias_final");
    private static final String FRONTEIRA = "ContratoClamaFronteira";
    private static final Pattern SET_COOKIE = Pattern.compile("\\{set_cookie:(\\d+):(.+)\\}", COMO_PYTHON);
    /** "configuracao.relogio": o instante que o gravador congelou (freezegun); nao e variavel de ambiente. */
    private static final String RELOGIO = "relogio";
    /** A senha temporaria (aleatoria) do esqueci minha senha, como o gravador a grava no e-mail. */
    static final String SENHA_TEMPORARIA = "<senha_temporaria>";
    /** gerar_senha_temporaria do Django: 14 caracteres sem os ambiguos (I, O, l, o, 0, 1). */
    private static final Pattern CANDIDATA_A_SENHA = Pattern.compile(
            "(?<![A-Za-z0-9])[A-HJ-NP-Za-km-np-z2-9]{14}(?![A-Za-z0-9])");
    /**
     * Divergencias de proposito nos jobs, por cenario: tipo gravado no Django -> tipo do Java. O reenviar do admin no
     * Django enfileira o mesmo enviar-oracao do pipeline, cuja chave de e-mail fixa faria a Resend devolver o envio
     * anterior em cache; o Java usa o reenviar-oracao, com chave nova a cada reenvio (contexto-global da 4C).
     */
    private static final Map<String, Map<String, String>> JOBS_DIVERGENTES = Map.of(
            "admin_reenviar_reenvia.json", Map.of("enviar-oracao", "reenviar-oracao"));

    private final JdbcTemplate jdbc;
    private final UsuarioRepository usuarios;
    private final ServicoDeTokens tokens;
    private final RateLimiter limiter;
    private final List<String> tabelas;
    private final SenhaDjango senhas = new SenhaDjango();
    private final HttpClient http = HttpClient.newHttpClient();
    private Map<String, StubDeIntegracoes> stubs = Map.of();
    private Environment ambiente;
    private CaixaDeSaidaEmMemoria caixa;
    private Collection<? extends TipoDeJob<?>> jobs;
    private GanchosDeOracao ganchos;
    private Consumer<Instant> relogio;

    /**
     * O que o harness precisa do app fora do banco: o cache de senhas temporarias (SenhasTemporarias) e o dedup em
     * memoria do alerta (AlertaDeErro), o cache.clear() do gravador.
     */
    public interface GanchosDeOracao {
        /** Antes de cada cenario: zera senhas temporarias e o dedup do alerta-admin. */
        void limpar();

        /** "senhas_temporarias" do cenario, em texto claro. */
        void guardarSenha(String chave, String senha);

        /** A senha em texto claro, se ainda estiver no cache. */
        Optional<String> senha(String chave);
    }

    /** tabelas: as que o cenario toca, truncadas (CASCADE) antes de cada execucao, como o User.objects.all().delete(). */
    public ReplayDeContrato(JdbcTemplate jdbc, UsuarioRepository usuarios, ServicoDeTokens tokens, RateLimiter limiter,
            List<String> tabelas) {
        this.jdbc = jdbc;
        this.usuarios = usuarios;
        this.tokens = tokens;
        this.limiter = limiter;
        this.tabelas = tabelas;
    }

    /** comStubs so com o Mercado Pago. */
    public ReplayDeContrato comStub(StubDeIntegracoes stub, Environment ambiente) {
        return comStubs(Map.of("mercadopago", stub), ambiente);
    }

    /**
     * Liga os stubs, um por integracao ("mercadopago", "anthropic"): cada um serve as respostas "<nome>" do cenario e,
     * no fim, as requisicoes recebidas tem de bater com "<nome>_requisicoes" (nenhuma, se o cenario nao declarar).
     * "configuracao" (o override_settings do gravador) tem de bater com o ambiente do contexto Spring.
     */
    public ReplayDeContrato comStubs(Map<String, StubDeIntegracoes> stubs, Environment ambiente) {
        this.stubs = stubs;
        this.ambiente = ambiente;
        return this;
    }

    /**
     * Cenarios da 4B: e-mails (a caixa de saida do perfil test), jobs enfileirados (scheduled_tasks), senhas
     * temporarias e cenarios de job, que rodam o {@link TipoDeJob} de mesmo nome direto, na thread do teste.
     */
    public ReplayDeContrato comOracao(CaixaDeSaidaEmMemoria caixa, Collection<? extends TipoDeJob<?>> jobs,
            GanchosDeOracao ganchos) {
        this.caixa = caixa;
        this.jobs = jobs;
        this.ganchos = ganchos;
        return this;
    }

    /**
     * Cenario com "configuracao.relogio" (o freezegun do gravador) fixa o relogio do app nesse instante; os outros o
     * soltam (null = relogio do sistema). Sem o gancho, cenario com relogio falha.
     */
    public ReplayDeContrato comRelogio(Consumer<Instant> relogio) {
        this.relogio = relogio;
        return this;
    }

    public void executar(Path cenarioJson, URI base) throws Exception {
        JsonNode cenario = JSON.readTree(cenarioJson.toFile());
        Set<String> fixos = uuidsFixos(cenario);
        String arquivo = cenarioJson.getFileName().toString();
        for (String integracao : INTEGRACOES) {
            assertThat(cenario.has(integracao) && !stubs.containsKey(integracao))
                    .as("%s declara %s: use comStubs", arquivo, integracao).isFalse();
        }
        assertThat(cenario.has("configuracao") && stubs.isEmpty()).as("%s declara configuracao: use comStubs", arquivo)
                .isFalse();
        assertThat(caixa == null && CHAVES_DE_ORACAO.stream().anyMatch(cenario::has))
                .as("%s e cenario da 4B: use comOracao", arquivo).isFalse();
        JsonNode instante = cenario.path("configuracao").path(RELOGIO);
        assertThat(!instante.isMissingNode() && relogio == null).as("%s congela o relogio: use comRelogio", arquivo)
                .isFalse();
        if (relogio != null) {
            relogio.accept(instante.isMissingNode() ? null : OffsetDateTime.parse(instante.asString()).toInstant());
        }
        for (Map.Entry<String, JsonNode> c : cenario.path("configuracao").properties()) {
            if (c.getKey().equals(RELOGIO)) {
                continue;
            }
            assertThat(ambiente.getProperty(c.getKey())).as("%s: rode num contexto com %s=%s", arquivo, c.getKey(),
                    c.getValue().asString()).isEqualTo(c.getValue().asString());
        }
        stubs.forEach((nome, stub) -> stub.preparar(cenario.path(nome)));
        BancoDeTeste.exigirContainer(jdbc); // o TRUNCATE ... CASCADE apagaria pedidos, blog e tokens de um banco real
        jdbc.execute("TRUNCATE " + String.join(", ", tabelas) + " RESTART IDENTITY CASCADE");
        limiter.limpar(); // cache.clear() do gravador
        if (caixa != null) {
            caixa.limpar();
            ganchos.limpar();
        }
        for (JsonNode u : cenario.path("usuarios")) {
            criarUsuario(u);
        }
        for (Map.Entry<String, JsonNode> tabela : cenario.path("dados").properties()) {
            for (JsonNode linha : tabela.getValue()) {
                inserir(tabela.getKey(), linha);
            }
        }
        for (Map.Entry<String, JsonNode> s : cenario.path("senhas_temporarias").properties()) {
            ganchos.guardarSenha(s.getKey(), s.getValue().asString());
        }
        if (cenario.has("job")) {
            executarJob(cenario.get("job").get("tipo").asString(), cenario.get("job").get("arg").asString(), arquivo);
        }

        Map<String, String> jar = new LinkedHashMap<>();
        Contexto ctx = new Contexto();
        int i = 0;
        for (JsonNode passo : cenario.path("passos")) {
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
            // "headers_resposta" do gravador: so os headers gravados, ausente = null, Expires (data HTTP) = <ts>
            JsonNode headers = passo.get("response").get("headers");
            if (headers != null) {
                ObjectNode h = obtido.putObject("headers");
                for (Map.Entry<String, JsonNode> e : headers.properties()) {
                    Optional<String> v = resp.headers().firstValue(e.getKey());
                    if (v.isEmpty()) {
                        h.putNull(e.getKey());
                    } else {
                        h.put(e.getKey(), e.getKey().equals("Expires") ? "<ts>" : v.get());
                    }
                }
            }
            assertThat(obtido).as("%s passo %d: %s %s", cenarioJson.getFileName(), i, metodo, req.get("path"))
                    .isEqualTo(passo.get("response"));
            i++;
        }
        stubs.forEach((nome, stub) -> stub.conferir(cenario.path(nome + "_requisicoes"), arquivo));
        if (cenario.has("emails")) {
            conferirEmails(cenario.get("emails"), fixos, arquivo);
        }
        if (cenario.has("jobs_enfileirados")) {
            conferirJobs(divergentes(cenario.get("jobs_enfileirados"), arquivo), fixos, arquivo);
        }
        if (cenario.has("senhas_temporarias_final")) {
            ArrayNode obtidas = NOS.arrayNode();
            for (Long id : jdbc.queryForList("SELECT id FROM users_user ORDER BY id", Long.class)) {
                for (String chave : List.of("doador:temp_password:" + id, "freemium:temp_password:" + id)) {
                    Optional<String> senha = ganchos.senha(chave);
                    if (senha.isPresent()) {
                        obtidas.add(chave);
                        // a senha que o app guardou (nao a semeada pelo cenario) tem de ser a do hash gravado
                        if (!cenario.path("senhas_temporarias").has(chave)) {
                            String hash = jdbc.queryForObject("SELECT password FROM users_user WHERE id = ?",
                                    String.class, id);
                            assertThat(senhas.matches(senha.get(), hash))
                                    .as("%s: %s confere com o hash do usuario", arquivo, chave).isTrue();
                        }
                    }
                }
            }
            assertThat((JsonNode) obtidas).as("%s: senhas temporarias no fim", arquivo)
                    .isEqualTo(cenario.get("senhas_temporarias_final"));
        }
        for (Map.Entry<String, JsonNode> tabela : cenario.path("estado_final").properties()) {
            conferirEstadoFinal(tabela.getKey(), tabela.getValue(), fixos, arquivo);
        }
    }

    /**
     * Cenario de job: o task.run(arg) do gravador. Roda o TipoDeJob de mesmo nome como a 1a execucao (retentativa 0),
     * sem transacao. O payload sai do arg: String, UUID, Long ou um record de um componente desses.
     */
    private void executarJob(String tipo, String arg, String arquivo) throws Exception {
        TipoDeJob<?> job = jobs.stream().filter(j -> j.nome().equals(tipo)).findFirst()
                .orElseThrow(() -> new AssertionError(arquivo + ": job " + tipo + " nao registrado"));
        executar(job, arg);
    }

    private static <P> void executar(TipoDeJob<P> job, String arg) throws Exception {
        job.executar(job.payload().cast(payload(job.payload(), arg)), 0);
    }

    static Object payload(Class<?> tipo, String arg) throws ReflectiveOperationException {
        if (tipo == String.class) {
            return arg;
        }
        if (tipo == UUID.class) {
            return UUID.fromString(arg);
        }
        if (tipo == Long.class || tipo == long.class) {
            return Long.valueOf(arg);
        }
        if (tipo.isRecord() && tipo.getRecordComponents().length == 1) {
            Class<?> componente = tipo.getRecordComponents()[0].getType();
            return tipo.getDeclaredConstructor(componente).newInstance(payload(componente, arg));
        }
        throw new IllegalArgumentException("payload de job sem conversao a partir do arg: " + tipo);
    }

    /**
     * _emails do gravador: o outbox do locmem. No Java, a CaixaDeSaidaEmMemoria do perfil test (sem stub HTTP da
     * Resend: a chave de idempotencia nao entra no contrato). reply_to nao e campo do Email: o gravado tem de ser o
     * fixo do ResendEnviador.
     */
    private void conferirEmails(JsonNode esperados, Set<String> fixos, String arquivo) {
        esperados.forEach(e -> assertThat(e.get("reply_to")).as("%s: reply_to gravado", arquivo)
                .isEqualTo(NOS.arrayNode().add(ResendEnviador.REPLY_TO)));
        boolean senhaTemporaria = esperados.toString().contains(SENHA_TEMPORARIA);
        ArrayNode obtidos = NOS.arrayNode();
        for (Email e : caixa.enviados()) {
            String hash = senhaTemporaria ? jdbc.queryForList("SELECT password FROM users_user WHERE email = ?",
                    String.class, e.para()).stream().findFirst().orElse(null) : null;
            ObjectNode o = obtidos.addObject();
            o.put("para", e.para());
            o.put("assunto", e.assunto());
            o.put("texto", hash == null ? e.texto() : semSenhaTemporaria(e.texto(), hash, senhas));
            o.put("html", hash == null ? e.html() : semSenhaTemporaria(e.html(), hash, senhas));
        }
        ArrayNode semReplyTo = NOS.arrayNode();
        esperados.forEach(e -> semReplyTo.add(((ObjectNode) e.deepCopy()).without(List.of("reply_to"))));
        assertThat(normalizar(obtidos, null, fixos)).as("%s: e-mails enviados", arquivo).isEqualTo(semReplyTo);
    }

    /**
     * A senha temporaria que o app mandou no e-mail vira {@link #SENHA_TEMPORARIA}, como no gravador: so a candidata
     * que confere com o hash do destinatario (prova tambem que a senha do e-mail e a gravada).
     */
    static String semSenhaTemporaria(String texto, String hash, SenhaDjango senhas) {
        return texto == null ? null : CANDIDATA_A_SENHA.matcher(texto).replaceAll(m -> Matcher.quoteReplacement(
                senhas.matches(m.group(), hash) ? SENHA_TEMPORARIA : m.group()));
    }

    /** jobs_enfileirados com os tipos de {@link #JOBS_DIVERGENTES} trocados pelos do Java, reordenados (tipo, arg). */
    private static JsonNode divergentes(JsonNode esperados, String arquivo) {
        Map<String, String> troca = JOBS_DIVERGENTES.get(arquivo);
        if (troca == null) {
            return esperados;
        }
        List<ObjectNode> jobs = new ArrayList<>();
        esperados.forEach(j -> jobs.add(((ObjectNode) j.deepCopy()).put("tipo",
                troca.getOrDefault(j.get("tipo").asString(), j.get("tipo").asString()))));
        jobs.sort(Comparator.<ObjectNode, String>comparing(j -> j.get("tipo").asString())
                .thenComparing(j -> j.get("arg").asString()));
        ArrayNode saida = NOS.arrayNode();
        jobs.forEach(saida::add);
        return saida;
    }

    /**
     * on_commit/.delay do gravador = linhas de scheduled_tasks (o scheduler fica desligado no perfil test). O payload
     * (task_data) so pode levar IDs: o arg e o ID (JSON string/numero, ou o 1o campo de um objeto cujos campos sao
     * todos IDs, como o {pedido, envio} do reenviar-oracao), e a instancia tem de ser exatamente "<tipo>/<arg>".
     * Ordenado por (tipo, arg) antes de normalizar, como o gravador.
     */
    private void conferirJobs(JsonNode esperados, Set<String> fixos, String arquivo) {
        List<String[]> linhas = new ArrayList<>(jdbc.query(
                "SELECT task_name, task_instance, task_data FROM scheduled_tasks", (rs, n) -> {
                    String tipo = rs.getString(1);
                    JsonNode dado = JSON.readTree(rs.getBytes(3));
                    List<JsonNode> ids = dado.isObject()
                            ? dado.properties().stream().map(Map.Entry::getValue).toList() : List.of(dado);
                    assertThat(ids).as("%s: payload de %s so com IDs: %s", arquivo, tipo, dado).isNotEmpty()
                            .allMatch(id -> id.isString() || id.isIntegralNumber());
                    String arg = ids.getFirst().asString();
                    assertThat(rs.getString(2)).as("%s: instancia do job %s", arquivo, tipo).isEqualTo(tipo + "/" + arg);
                    return new String[] {tipo, arg};
                }));
        linhas.sort(Comparator.<String[], String>comparing(l -> l[0]).thenComparing(l -> l[1]));
        ArrayNode obtidos = NOS.arrayNode();
        linhas.forEach(l -> obtidos.addObject().put("tipo", l[0]).put("arg", l[1]));
        assertThat(normalizar(obtidos, null, fixos)).as("%s: jobs enfileirados", arquivo).isEqualTo(esperados);
    }

    /**
     * _estado_final do gravador: todas as linhas da tabela depois do ultimo passo, nas colunas que o JSON lista, com
     * as cifradas decifradas (CampoCriptografado), json como arvore, normalizadas como as respostas. Numero compara
     * por valor.
     */
    private void conferirEstadoFinal(String nome, JsonNode tabela, Set<String> fixos, String arquivo) {
        String sql = SQL_ESTADO.get(nome);
        assertThat(sql).as("%s: estado final de tabela desconhecida %s", arquivo, nome).isNotNull();
        List<String> colunas = new ArrayList<>();
        tabela.get("colunas").forEach(c -> colunas.add(c.asString()));
        Set<String> cifradas = new HashSet<>();
        tabela.get("cifradas_decifradas").forEach(c -> cifradas.add(c.asString()));
        CampoCriptografado campo = new CampoCriptografado();
        ArrayNode linhas = NOS.arrayNode();
        jdbc.query(sql, (RowCallbackHandler) rs -> {
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
                } else if (v instanceof PGobject json && json.getType().startsWith("json")) {
                    l.set(c, JSON.readTree(json.getValue()));
                } else {
                    l.put(c, rs.getString(c)); // timestamptz, uuid, inet como texto do Postgres
                }
            }
        });
        assertThat(StubDeIntegracoes.numerosPorValor(normalizar(linhas, null, fixos)))
                .as("%s: estado final de %s", arquivo, nome)
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
        if (d.hasNonNull("freemium_used_at")) {
            u.setFreemiumUsedAt(OffsetDateTime.parse(d.get("freemium_used_at").asString()));
        }
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

    /** uuids_fixos do gravador: os UUIDs das linhas de dados, dos requests e das respostas simuladas. */
    static Set<String> uuidsFixos(JsonNode cenario) {
        StringBuilder texto = new StringBuilder(cenario.path("dados").toString());
        cenario.path("passos").forEach(p -> texto.append(p.path("request")));
        INTEGRACOES.forEach(i -> texto.append(cenario.path(i))); // as respostas simuladas tambem sao do cenario
        return UUID_TEXTO.matcher(texto).results().map(r -> r.group()).collect(Collectors.toSet());
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
            return NOS.stringNode(UUID_TEXTO.matcher(valor.asString())
                    .replaceAll(m -> fixos.contains(m.group()) ? m.group() : "<uuid>"));
        }
        return valor;
    }
}
