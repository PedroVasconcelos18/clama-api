package me.clama.api.instituicao.repasse;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import me.clama.api.BancoDeTeste;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
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

/** GET /api/admin/repasses/ e /{id}/: paginacao, filtros, 400 de filtro malformado, 404, 401 e 403. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class RepassesAdminTest {
    static final String PLANO = "55555555-5555-4555-8555-000000000002";
    static final String RENAL = "66666666-6666-4666-8666-000000000001";
    static final String MAR = "66666666-6666-4666-8666-000000000002";

    @Value("${local.server.port}")
    int porta;
    @Autowired JdbcTemplate jdbc;
    @Autowired UsuarioRepository usuarios;
    @Autowired ServicoDeTokens tokens;

    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();
    String cookie;
    String cookieComum;

    @BeforeEach
    void preparar() {
        BancoDeTeste.exigirContainer(jdbc);
        jdbc.execute("TRUNCATE instituicoes_repasse, instituicoes_instituicao, orders_pedido, users_user CASCADE");
        Usuario a = new Usuario();
        a.setEmail("admin-repasses@exemplo.com");
        a.setPassword("x");
        a.setClamaAdmin(true);
        cookie = "clama_access=" + tokens.emitirPar(usuarios.saveAndFlush(a)).access();
        Usuario c = new Usuario();
        c.setEmail("comum-repasses@exemplo.com");
        c.setPassword("x");
        cookieComum = "clama_access=" + tokens.emitirPar(usuarios.saveAndFlush(c)).access();
        jdbc.update("""
                insert into plans_plan (created_at, updated_at, id, nome, valor_centavos, descricao, complexidade,
                    ordem, ativo, visivel) values (now(), now(), ?::uuid, 'P', 2000, 'd', 'simples', 1, true, true)
                on conflict (id) do nothing""", PLANO);
        inst(RENAL, "Instituto Renal 100%");
        inst(MAR, "Mar de Espanha");
    }

    private void inst(String id, String nome) {
        jdbc.update("""
                insert into instituicoes_instituicao (created_at, updated_at, id, nome, ativo, ordem, logo)
                values (now(), now(), ?::uuid, ?, true, 0, '')""", id, nome);
    }

    /** Repasse com created_at = agora - minutos, para a ordem ser previsivel. */
    private UUID repasse(String inst, String status, int minutosAtras) {
        UUID pedido = UUID.randomUUID();
        jdbc.update("""
                insert into orders_pedido (created_at, updated_at, id, nome, email, telefone, sexo, pedido_oracao,
                    oracao_gerada, valor_centavos, canal_entrega, status, plano_id, consent_aceito, eh_gratuito,
                    retry_count, last_error, consent_versao, whatsapp_message_id, cpf_cnpj, device_hash)
                values (now(), now(), ?, 'Fulano Secreto', 'secreto@exemplo.com', '', '', '', '', 2000, 'email',
                    'pago', ?::uuid, true, false, 0, '', '', '', '', '')""", pedido, PLANO);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into instituicoes_repasse (created_at, updated_at, id, pedido_id, instituicao_id,
                    valor_base_centavos, percentual, valor_centavos, status, transferido_em, comprovante)
                values (now() - ? * interval '1 minute', now(), ?, ?, ?::uuid, 2000, 20, 400, ?,
                    case when ? = 'transferido' then now() end, case when ? = 'transferido' then 'E2E123' else '' end)""",
                minutosAtras, id, pedido, inst, status, status, status);
        return id;
    }

    private HttpResponse<String> req(String caminho, String ck) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho));
        if (ck != null) {
            b.header("Cookie", ck);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode get(String caminho) throws Exception {
        HttpResponse<String> r = req("/api/admin/repasses/" + caminho, cookie);
        assertThat(r.statusCode()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private static List<String> ids(JsonNode lista) {
        List<String> r = new ArrayList<>();
        lista.get("results").forEach(n -> r.add(n.get("id").asString()));
        return r;
    }

    @Test
    void listaComChavesEmOrdemFixaEOrdemDecrescente() throws Exception {
        UUID velho = repasse(RENAL, "registrado", 10);
        UUID novo = repasse(MAR, "transferido", 1);
        JsonNode l = get("");
        assertThat(l.get("count").asInt()).isEqualTo(2);
        assertThat(ids(l)).containsExactly(novo.toString(), velho.toString());
        assertThat(l.propertyNames()).containsExactly("count", "next", "previous", "results");
        JsonNode item = l.get("results").get(0);
        assertThat(item.propertyNames()).containsExactly("id", "pedido_id", "instituicao_id", "instituicao_nome",
                "valor_base_centavos", "percentual", "valor_centavos", "status", "transferido_em", "comprovante",
                "created_at", "updated_at");
        assertThat(item.get("instituicao_nome").asString()).isEqualTo("Mar de Espanha");
        assertThat(item.get("valor_centavos").asInt()).isEqualTo(400);
        assertThat(item.get("transferido_em").isNull()).isFalse();
        assertThat(item.get("comprovante").asString()).isEqualTo("E2E123");
        assertThat(l.get("results").get(1).get("transferido_em").isNull()).isTrue();
        // sem PII do pedido
        assertThat(l.toString()).doesNotContain("Secreto").doesNotContain("secreto@");
    }

    @Test
    void paginacaoLimitOffset() throws Exception {
        for (int i = 0; i < 3; i++) {
            repasse(RENAL, "registrado", 10 - i);
        }
        JsonNode p1 = get("?limit=2");
        assertThat(p1.get("count").asInt()).isEqualTo(3);
        assertThat(p1.get("results")).hasSize(2);
        assertThat(p1.get("next").asString()).contains("limit=2").contains("offset=2");
        assertThat(p1.get("previous").isNull()).isTrue();
        JsonNode p2 = get("?limit=2&offset=2");
        assertThat(p2.get("results")).hasSize(1);
        assertThat(p2.get("next").isNull()).isTrue();
        assertThat(p2.get("previous").isNull()).isFalse();
        assertThat(ids(p1)).doesNotContainAnyElementsOf(ids(p2));
    }

    @Test
    void filtroDeStatusEIgnoraValorDesconhecido() throws Exception {
        repasse(RENAL, "registrado", 3);
        repasse(RENAL, "transferido", 2);
        assertThat(get("?status=transferido").get("count").asInt()).isEqualTo(1);
        assertThat(get("?status=registrado").get("count").asInt()).isEqualTo(1);
        assertThat(get("?status=xyz").get("count").asInt()).isEqualTo(2);
        // repetido: vale o ultimo
        assertThat(get("?status=registrado&status=transferido").get("results").get(0).get("status").asString())
                .isEqualTo("transferido");
    }

    @Test
    void filtroDeInstituicao() throws Exception {
        repasse(RENAL, "registrado", 3);
        repasse(MAR, "registrado", 2);
        JsonNode l = get("?instituicao=" + MAR);
        assertThat(l.get("count").asInt()).isEqualTo(1);
        assertThat(l.get("results").get(0).get("instituicao_id").asString()).isEqualTo(MAR);
    }

    @Test
    void buscaPorNomeComEscapeDeCuringas() throws Exception {
        repasse(RENAL, "registrado", 3);
        repasse(MAR, "registrado", 2);
        assertThat(get("?q=mar%20de").get("count").asInt()).isEqualTo(1);
        assertThat(get("?q=100%25").get("count").asInt()).isEqualTo(1); // "%" literal
        assertThat(get("?q=%25").get("count").asInt()).isEqualTo(1);    // so o nome com %, nao tudo
        assertThat(get("?q=_").get("count").asInt()).isZero();           // "_" literal
        assertThat(get("?q=nada").get("count").asInt()).isZero();
    }

    @Test
    void instituicaoMalformadaE400() throws Exception {
        HttpResponse<String> r = req("/api/admin/repasses/?instituicao=nao-e-uuid", cookie);
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json.readTree(r.body()).get("detail").asString()).isEqualTo("Parâmetro instituicao inválido.");
    }

    @Test
    void detalheEDetalhe404Pastoral() throws Exception {
        UUID id = repasse(RENAL, "registrado", 1);
        HttpResponse<String> r = req("/api/admin/repasses/" + id + "/", cookie);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(json.readTree(r.body()).get("id").asString()).isEqualTo(id.toString());
        HttpResponse<String> nf = req("/api/admin/repasses/" + UUID.randomUUID() + "/", cookie);
        assertThat(nf.statusCode()).isEqualTo(404);
        JsonNode erro = json.readTree(nf.body()).get("error");
        assertThat(erro.get("code").asString()).isEqualTo("not_found");
        assertThat(erro.get("pastoral_message").asString()).isNotBlank();
    }

    @Test
    void naoAdmin403EAnonimo401() throws Exception {
        UUID id = repasse(RENAL, "registrado", 1);
        for (String c : List.of("/api/admin/repasses/", "/api/admin/repasses/" + id + "/")) {
            HttpResponse<String> comum = req(c, cookieComum);
            assertThat(comum.statusCode()).as(c).isEqualTo(403);
            assertThat(json.readTree(comum.body()).get("detail").asString())
                    .isEqualTo("Esse espaço é só para admins do Clama.");
            HttpResponse<String> anon = req(c, null);
            assertThat(anon.statusCode()).as(c).isEqualTo(401);
            assertThat(json.readTree(anon.body()).get("error").get("code").asString()).isEqualTo("not_authenticated");
        }
    }
}
