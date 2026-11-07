package me.clama.api.identidade.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.clama.api.BancoDeTeste;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.senha.SenhaDjango;
import me.clama.api.identidade.token.ParDeTokens;
import me.clama.api.identidade.token.ServicoDeTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Usuarios admin (mapa 7 §4.4): escritas em /api/admin/customers/ so para superusuario (D3), D5-D7, D17 e D18. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"ANTHROPIC_API_KEY=",
        "TURNSTILE_SECRET_KEY="})
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class UsuariosAdminTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String BASE = "/api/admin/customers/";
    static final String SENHA_BOA = "Rocha-firme-2026";

    @Value("${local.server.port}")
    int porta;
    @Autowired JdbcTemplate jdbc;
    @Autowired UsuarioRepository usuarios;
    @Autowired ServicoDeTokens tokens;
    @Autowired SenhaDjango senhas;

    final HttpClient http = HttpClient.newHttpClient();
    long superId;
    String csrf;
    String cookieSuper;
    String cookieAdmin;

    @BeforeEach
    void preparar() throws Exception {
        BancoDeTeste.exigirContainer(jdbc);
        jdbc.execute("TRUNCATE users_user, django_admin_log RESTART IDENTITY CASCADE");
        jdbc.update("insert into django_content_type (app_label, model) values ('users', 'user') on conflict do nothing");
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri(BASE.replace("admin/customers", "csrf")))
                .build(), HttpResponse.BodyHandlers.ofString());
        csrf = JSON.readTree(r.body()).get("csrf_token").asString();
        Usuario s = usuario("super@exemplo.com", true);
        s.setSuperuser(true);
        superId = usuarios.saveAndFlush(s).getId();
        cookieSuper = cookie(s);
        cookieAdmin = cookie(usuario("admin@exemplo.com", true));
    }

    /** Bans e tokens que sobram quebrariam o "delete from users_user" da limpeza de outras classes. */
    @org.junit.jupiter.api.AfterEach
    void limpar() {
        jdbc.execute("TRUNCATE users_user, django_admin_log CASCADE");
    }

    URI uri(String caminho) {
        return URI.create("http://localhost:" + porta + caminho);
    }

    Usuario usuario(String email, boolean admin) {
        Usuario u = new Usuario();
        u.setEmail(email);
        u.setPassword("x");
        u.setClamaAdmin(admin);
        u.setStaff(admin);
        return usuarios.saveAndFlush(u);
    }

    String cookie(Usuario u) {
        return "clama_access=" + tokens.emitirPar(u).access() + "; csrftoken=" + csrf;
    }

    HttpResponse<String> chamar(String metodo, String caminho, String corpo, String ck, boolean comCsrf)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(BASE + caminho)).header("Content-Type", "application/json")
                .method(metodo, corpo == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(corpo));
        if (ck != null) {
            b.header("Cookie", ck);
        }
        if (comCsrf) {
            b.header("X-CSRFToken", csrf);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> superusuario(String metodo, String caminho, String corpo) throws Exception {
        return chamar(metodo, caminho, corpo, cookieSuper, true);
    }

    JsonNode corpo(HttpResponse<String> r, int status) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        return JSON.readTree(r.body());
    }

    String codigo409(HttpResponse<String> r) {
        return corpo(r, 409).get("error").get("code").asString();
    }

    Map<String, Object> linha(long id) {
        return jdbc.queryForMap("select email, is_clama_admin, is_staff, is_superuser, is_active, force_change_password,"
                + " password from users_user where id = ?", id);
    }

    /** Refresh ainda valido: outstanding do usuario sem linha na blacklist. */
    int sessoesVivas(long id) {
        return jdbc.queryForObject("""
                select count(*) from token_blacklist_outstandingtoken t where t.user_id = ? and not exists
                    (select 1 from token_blacklist_blacklistedtoken b where b.token_id = t.id)""", Integer.class, id);
    }

    List<Map<String, Object>> auditoria() {
        return jdbc.queryForList("select object_id, object_repr, action_flag, change_message, user_id from "
                + "django_admin_log order by id");
    }

    @Test
    void adminNaoSuperusuario403() throws Exception {
        long alvo = usuario("cliente@exemplo.com", false).getId();
        for (String[] m : new String[][] {{"POST", ""}, {"PATCH", alvo + "/"}, {"POST", alvo + "/senha/"},
                {"DELETE", alvo + "/"}}) {
            HttpResponse<String> r = chamar(m[0], m[1], "{\"password\": \"" + SENHA_BOA + "\", \"email\": "
                    + "\"x@exemplo.com\", \"is_active\": false}", cookieAdmin, true);
            assertThat(corpo(r, 403).get("detail").asString()).as(m[0] + " " + m[1])
                    .isEqualTo("Só o superusuário gerencia usuários.");
        }
        // leitura continua aberta a todo admin; nada mudou
        assertThat(chamar("GET", alvo + "/", null, cookieAdmin, false).statusCode()).isEqualTo(200);
        assertThat(linha(alvo).get("is_active")).isEqualTo(true);
        assertThat(usuarios.existsByEmail("x@exemplo.com")).isFalse();
        assertThat(auditoria()).isEmpty();
        // customer comum segue com o 403 de sempre
        String cookieCliente = cookie(usuarios.findById(alvo).orElseThrow());
        assertThat(corpo(chamar("DELETE", alvo + "/", null, cookieCliente, true), 403).get("detail").asString())
                .isEqualTo("Esse espaço é só para admins do Clama.");
    }

    @Test
    void anonimo401() throws Exception {
        HttpResponse<String> r = chamar("POST", "", "{}", null, false);
        assertThat(corpo(r, 401).get("error").get("code").asString()).isEqualTo("not_authenticated");
    }

    @Test
    void csrfObrigatorio403() throws Exception {
        long alvo = usuario("cliente@exemplo.com", false).getId();
        HttpResponse<String> r = chamar("PATCH", alvo + "/", "{\"is_active\": false}", cookieSuper, false);
        assertThat(corpo(r, 403).get("error").get("code").asString()).isEqualTo("csrf_invalido");
        assertThat(linha(alvo).get("is_active")).isEqualTo(true);
    }

    @Test
    void criarAdminComIsStaff() throws Exception {
        JsonNode d = corpo(superusuario("POST", "", "{\"email\": \" Novo.Admin@Exemplo.COM \", \"nome_completo\": "
                + "\"Ana Lima\", \"password\": \"" + SENHA_BOA + "\", \"is_superuser\": true}"), 201);
        long id = d.get("id").asLong();
        assertThat(d.get("email").asString()).isEqualTo("novo.admin@exemplo.com");
        assertThat(d.get("nome_completo").asString()).isEqualTo("Ana Lima");
        assertThat(d.get("is_clama_admin").asBoolean()).isTrue();
        assertThat(d.has("password")).isFalse();
        Map<String, Object> l = linha(id);
        assertThat(l.get("is_clama_admin")).isEqualTo(true);
        assertThat(l.get("is_staff")).isEqualTo(true);
        assertThat(l.get("is_superuser")).isEqualTo(false);
        assertThat(l.get("is_active")).isEqualTo(true);
        assertThat(l.get("force_change_password")).isEqualTo(false);
        assertThat((String) l.get("password")).startsWith("argon2$");
        assertThat(senhas.matches(SENHA_BOA, (String) l.get("password"))).isTrue();
        assertThat(auditoria()).singleElement().satisfies(a -> {
            assertThat(a.get("object_id")).isEqualTo(String.valueOf(id));
            assertThat(a.get("object_repr")).isEqualTo(String.valueOf(id));
            assertThat(a.get("action_flag")).isEqualTo(1);
            assertThat(a.get("change_message")).isEqualTo("[{\"added\":{}}]");
            assertThat(a.get("user_id")).isEqualTo(superId);
        });

        // e-mail repetido, comparado em minusculas ou pelo hash canonico (inclusive o de um customer: promover e pelo
        // PATCH); a variante Gmail com ponto e +tag de um doador cairia na mesma conta no provisionamento
        usuario("Cliente@Exemplo.com", false);
        usuario("mariasilva@gmail.com", false);
        for (String email : List.of("NOVO.ADMIN@exemplo.com", "cliente@exemplo.com", "Maria.Silva+x@gmail.com")) {
            JsonNode e = corpo(superusuario("POST", "", "{\"email\": \"" + email + "\", \"password\": \"" + SENHA_BOA
                    + "\"}"), 400);
            assertThat(e.get("email").get(0).asString()).as(email).isEqualTo("Usuário com este E-mail já existe.");
        }
        assertThat(jdbc.queryForObject("select count(*) from users_user", Integer.class)).isEqualTo(5);
        // campos: obrigatorio, e-mail invalido
        JsonNode e = corpo(superusuario("POST", "", "{\"email\": \"nao-e-email\"}"), 400);
        assertThat(e.get("email").get(0).asString()).isEqualTo("Insira um endereço de email válido.");
        assertThat(e.get("password").get(0).asString()).isEqualTo("Este campo é obrigatório.");
        assertThat(auditoria()).hasSize(1);
    }

    @Test
    void senhaFracaTextosDrf() throws Exception {
        JsonNode e = corpo(superusuario("POST", "", "{\"email\": \"maria@exemplo.com\", \"password\": \"123\"}"), 400);
        assertThat(e.get("password")).extracting(JsonNode::asString).containsExactly(
                "Esta senha é muito curta. Ela precisa conter pelo menos 8 caracteres.", "Esta senha é muito comum.",
                "Esta senha é inteiramente numérica.");
        // similaridade com o e-mail: no criar, o e-mail do corpo; no definir senha, o do usuario alvo
        e = corpo(superusuario("POST", "", "{\"email\": \"joaquimsilveira@exemplo.com\", \"password\": "
                + "\"joaquimsilveira\"}"), 400);
        assertThat(e.get("password").get(0).asString()).isEqualTo("A senha é muito parecida com E-mail");
        long alvo = usuario("joaquimsilveira@exemplo.com", false).getId();
        e = corpo(superusuario("POST", alvo + "/senha/", "{\"password\": \"joaquimsilveira\"}"), 400);
        assertThat(e.get("password").get(0).asString()).isEqualTo("A senha é muito parecida com E-mail");
        assertThat(linha(alvo).get("password")).isEqualTo("x");
        assertThat(usuarios.existsByEmail("maria@exemplo.com")).isFalse();
        assertThat(auditoria()).isEmpty();
    }

    @Test
    void definirSenhaValidaERevogaSessoes() throws Exception {
        Usuario novo = usuario("cliente@exemplo.com", false);
        novo.setForceChangePassword(true);
        Usuario u = usuarios.saveAndFlush(novo);
        ParDeTokens par = tokens.emitirPar(u);
        tokens.emitirPar(u);
        assertThat(sessoesVivas(u.getId())).isEqualTo(2);

        JsonNode d = corpo(superusuario("POST", u.getId() + "/senha/", "{\"password\": \"" + SENHA_BOA + "\"}"), 200);
        assertThat(JSON.writeValueAsString(d)).isEqualTo("{\"detail\":\"Senha definida.\",\"pastoral_message\":"
                + "\"Senha definida — a pessoa precisa entrar de novo.\"}");
        Map<String, Object> l = linha(u.getId());
        assertThat(senhas.matches(SENHA_BOA, (String) l.get("password"))).isTrue();
        assertThat(l.get("force_change_password")).isEqualTo(true); // D5
        assertThat(sessoesVivas(u.getId())).isZero(); // D6
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tokens.rotacionar(par.refresh()))
                .isInstanceOf(me.clama.api.identidade.token.JwtInvalido.class);
        assertThat(auditoria()).singleElement().satisfies(a -> {
            assertThat(a.get("action_flag")).isEqualTo(2);
            assertThat(a.get("change_message")).isEqualTo("[{\"changed\":{\"fields\":[\"password\"]}}]");
            assertThat(a.get("object_repr")).isEqualTo(String.valueOf(u.getId()));
        });
        assertThat(superusuario("POST", "999999999/senha/", "{\"password\": \"" + SENHA_BOA + "\"}").statusCode())
                .isEqualTo(404);
    }

    @Test
    void desativarRevogaSessoes() throws Exception {
        Usuario u = usuario("cliente@exemplo.com", false);
        String acessoDoAlvo = cookie(u);
        assertThat(sessoesVivas(u.getId())).isOne();

        JsonNode d = corpo(superusuario("PATCH", u.getId() + "/", "{\"is_active\": false}"), 200);
        assertThat(d.get("is_active").asBoolean()).isFalse();
        assertThat(sessoesVivas(u.getId())).isZero();
        // o filtro rele o usuario: o access que ainda nao venceu ja leva 401
        assertThat(chamar("GET", "", null, acessoDoAlvo, false).statusCode()).isEqualTo(401);
        assertThat(auditoria()).singleElement().satisfies(a -> assertThat(a.get("change_message"))
                .isEqualTo("[{\"changed\":{\"fields\":[\"active\"]}}]"));

        // reativar nao revoga nada (nem ha o que revogar) e grava a mudanca
        tokens.emitirPar(usuarios.findById(u.getId()).orElseThrow());
        assertThat(corpo(superusuario("PATCH", u.getId() + "/", "{\"is_active\": true}"), 200).get("is_active")
                .asBoolean()).isTrue();
        assertThat(sessoesVivas(u.getId())).isOne();
        // PATCH sem mudanca: 200, sem auditoria
        corpo(superusuario("PATCH", u.getId() + "/", "{\"is_active\": true}"), 200);
        assertThat(auditoria()).hasSize(2);
        // booleano invalido: 400 do DRF
        assertThat(corpo(superusuario("PATCH", u.getId() + "/", "{\"is_active\": \"talvez\"}"), 400).get("is_active")
                .get(0).asString()).isEqualTo("Deve ser um valor booleano válido.");
    }

    @Test
    void removerAdminRevogaSessoesESincronizaStaff() throws Exception {
        Usuario a = usuario("outro-admin@exemplo.com", true);
        tokens.emitirPar(a);
        JsonNode d = corpo(superusuario("PATCH", a.getId() + "/", "{\"is_clama_admin\": false}"), 200);
        assertThat(d.get("is_clama_admin").asBoolean()).isFalse();
        assertThat(linha(a.getId()).get("is_staff")).isEqualTo(false);
        assertThat(sessoesVivas(a.getId())).isZero();
        assertThat(auditoria()).singleElement().satisfies(x -> assertThat(x.get("change_message"))
                .isEqualTo("[{\"changed\":{\"fields\":[\"staff status\",\"Admin do Clama\"]}}]"));
    }

    @Test
    void promoverComSenhaTemporaria409() throws Exception {
        Usuario u = usuario("doador@exemplo.com", false);
        u.setForceChangePassword(true);
        usuarios.saveAndFlush(u);
        assertThat(codigo409(superusuario("PATCH", u.getId() + "/", "{\"is_clama_admin\": true}")))
                .isEqualTo("senha_temporaria");
        assertThat(linha(u.getId()).get("is_clama_admin")).isEqualTo(false);
        assertThat(auditoria()).isEmpty();
    }

    @Test
    void promoverBanido409() throws Exception {
        long u = usuario("banido@exemplo.com", false).getId();
        jdbc.update("""
                insert into blog_customerbanido (created_at, updated_at, id, motivo, banido_em, banido_por_id,
                    customer_id) values (now(), now(), ?, 'Ofensa', now(), ?, ?)""", UUID.randomUUID(), superId, u);
        assertThat(codigo409(superusuario("PATCH", u + "/", "{\"is_clama_admin\": true}")))
                .isEqualTo("customer_banido");
        assertThat(linha(u).get("is_clama_admin")).isEqualTo(false);
        // ban revogado nao impede
        jdbc.update("update blog_customerbanido set revogado_em = now(), revogado_por_id = ?", superId);
        assertThat(corpo(superusuario("PATCH", u + "/", "{\"is_clama_admin\": true}"), 200).get("is_clama_admin")
                .asBoolean()).isTrue();
        assertThat(linha(u).get("is_staff")).isEqualTo(true);
    }

    @Test
    void superusuarioNaoMudaPelaTela() throws Exception {
        long u = usuario("cliente@exemplo.com", false).getId();
        corpo(superusuario("PATCH", u + "/", "{\"is_superuser\": true, \"is_staff\": false, \"is_clama_admin\": true, "
                + "\"email\": \"trocado@exemplo.com\", \"password\": \"x\"}"), 200);
        Map<String, Object> l = linha(u);
        assertThat(l.get("is_superuser")).isEqualTo(false);
        assertThat(l.get("is_clama_admin")).isEqualTo(true);
        assertThat(l.get("is_staff")).isEqualTo(true); // D7: acompanha is_clama_admin, nao o corpo
        assertThat(l.get("email")).isEqualTo("cliente@exemplo.com"); // D18
        assertThat(l.get("password")).isEqualTo("x");
        // rebaixar um superusuario nao tira o is_superuser
        Usuario s2 = usuario("super2@exemplo.com", true);
        s2.setSuperuser(true);
        usuarios.saveAndFlush(s2);
        corpo(superusuario("PATCH", s2.getId() + "/", "{\"is_clama_admin\": false, \"is_superuser\": false}"), 200);
        assertThat(linha(s2.getId()).get("is_superuser")).isEqualTo(true);
    }

    /**
     * Dois superusuarios rebaixando um ao outro ao mesmo tempo: o teste segura o lock "admins" ate os dois PATCH
     * pararem nele. O primeiro passa; o segundo ja ve o outro rebaixado e leva ultimo_superusuario (sem a guarda, os
     * dois passariam e ninguem mais geriria usuarios). Ha outro admin comum ativo, entao nao e o ultimo_admin.
     */
    @Test
    void superusuariosSeRebaixandoAoMesmoTempo() throws Exception {
        Usuario s2 = usuario("super2@exemplo.com", true);
        s2.setSuperuser(true);
        s2 = usuarios.saveAndFlush(s2);
        String cookieSuper2 = cookie(s2);
        long id2 = s2.getId();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try (java.sql.Connection trava = jdbc.getDataSource().getConnection()) {
            trava.setAutoCommit(false);
            trava.createStatement().execute("select pg_advisory_xact_lock(hashtext('admins'))");
            String corpo = "{\"is_clama_admin\": false}";
            List<java.util.concurrent.Future<HttpResponse<String>>> fs = List.of(
                    pool.submit(() -> chamar("PATCH", id2 + "/", corpo, cookieSuper, true)),
                    pool.submit(() -> chamar("PATCH", superId + "/", corpo, cookieSuper2, true)));
            long limite = System.nanoTime() + 10_000_000_000L;
            while (jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type = 'Lock'"
                    + " and wait_event = 'advisory' and query ilike '%pg_advisory_xact_lock%'", Long.class) < 2) {
                assertThat(System.nanoTime()).as("os dois PATCH nao pararam no advisory lock").isLessThan(limite);
                Thread.sleep(20);
            }
            trava.commit();
            List<HttpResponse<String>> rs = new java.util.ArrayList<>();
            for (var f : fs) {
                rs.add(f.get(10, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertThat(rs).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
            assertThat(codigo409(rs.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow()))
                    .isEqualTo("ultimo_superusuario");
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from users_user where is_superuser and is_clama_admin and "
                + "is_active", Integer.class)).isOne();
    }

    @Test
    void alterarOuExcluirAPropriaConta409() throws Exception {
        assertThat(codigo409(superusuario("PATCH", superId + "/", "{\"is_active\": false}")))
                .isEqualTo("nao_pode_alterar_a_si_mesmo");
        assertThat(codigo409(superusuario("DELETE", superId + "/", null))).isEqualTo("nao_pode_excluir_a_si_mesmo");
        assertThat(linha(superId).get("is_active")).isEqualTo(true);
    }

    @Test
    void excluirPelaTela() throws Exception {
        long u = usuario("cliente@exemplo.com", false).getId();
        assertThat(superusuario("DELETE", u + "/", null).statusCode()).isEqualTo(204);
        assertThat(usuarios.existsById(u)).isFalse();
        assertThat(auditoria()).singleElement().satisfies(a -> {
            assertThat(a.get("action_flag")).isEqualTo(3);
            assertThat(a.get("change_message")).isEqualTo("");
            assertThat(a.get("object_repr")).isEqualTo(String.valueOf(u));
        });
        assertThat(superusuario("DELETE", u + "/", null).statusCode()).isEqualTo(404);
        // a guarda do servico chega como 409 pastoral
        long outroAdmin = usuario("outro-admin@exemplo.com", true).getId();
        long cliente = usuario("cliente2@exemplo.com", false).getId();
        jdbc.update("""
                insert into blog_customerbanido (created_at, updated_at, id, motivo, banido_em, banido_por_id,
                    customer_id) values (now(), now(), ?, 'Ofensa', now(), ?, ?)""", UUID.randomUUID(), outroAdmin,
                cliente);
        assertThat(codigo409(superusuario("DELETE", outroAdmin + "/", null))).isEqualTo("usuario_baniu_alguem");
        assertThat(auditoria()).hasSize(1);
    }

    @Test
    void filtroAdmin() throws Exception {
        usuario("cliente@exemplo.com", false);
        JsonNode admins = corpo(chamar("GET", "?admin=false&admin=true", null, cookieAdmin, false), 200);
        assertThat(admins.get("results")).extracting(n -> n.get("email").asString())
                .containsExactlyInAnyOrder("super@exemplo.com", "admin@exemplo.com");
        JsonNode clientes = corpo(chamar("GET", "?admin=false", null, cookieAdmin, false), 200);
        assertThat(clientes.get("results")).extracting(n -> n.get("email").asString())
                .containsExactly("cliente@exemplo.com");
        assertThat(corpo(chamar("GET", "?admin=talvez", null, cookieAdmin, false), 200).get("count").asInt())
                .isEqualTo(3);
    }
}
