package me.clama.api.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import me.clama.api.BancoDeTeste;
import me.clama.api.contrato.StubDeIntegracoes;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.token.ServicoDeTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

/** O que o contrato gravado nao cobre da previa: erro generico (500 com a mensagem, sem retry) e o corpo do erro. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ANTHROPIC_API_KEY=sk-ant-previa")
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class PreviaDePromptTest {
    static final StubDeIntegracoes ANTHROPIC = new StubDeIntegracoes("requisicoes a Anthropic",
            Map.of("chave", "x-api-key", "versao", "anthropic-version", "beta", "anthropic-beta"));
    static final ObjectMapper JSON = new ObjectMapper();
    static final String ID = "88888888-8888-4888-8888-000000000009";

    @DynamicPropertySource
    static void stub(DynamicPropertyRegistry r) {
        r.add("clama.anthropic.url", ANTHROPIC::url);
    }

    @AfterAll
    static void parar() {
        ANTHROPIC.parar();
    }

    @Value("${local.server.port}")
    int porta;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UsuarioRepository usuarios;

    @Autowired
    ServicoDeTokens tokens;

    String cookies;
    String csrf;

    @BeforeEach
    void preparar() throws Exception {
        BancoDeTeste.exigirContainer(jdbc);
        jdbc.execute("TRUNCATE prompts_prompttemplate, users_user CASCADE");
        jdbc.update("""
                insert into prompts_prompttemplate (id, nome, versao, system_prompt, instrucoes_por_complexidade, ativo,
                  created_at, updated_at) values (?::uuid, 'previa', 1, 'Sistema.', '{}', false, now(), now())""", ID);
        Usuario u = new Usuario();
        u.setEmail("admin-previa@exemplo.com");
        u.setPassword("x");
        u.setNomeCompleto("Admin");
        u.setClamaAdmin(true);
        u.setActive(true);
        usuarios.saveAndFlush(u);
        String access = "clama_access=" + tokens.emitirPar(u).access();
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/api/csrf/"))
                        .header("Cookie", access).build(), HttpResponse.BodyHandlers.ofString());
        csrf = JSON.readTree(r.body()).get("csrf_token").asString();
        cookies = access + "; " + r.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("csrftoken="))
                .findFirst().orElseThrow().split(";")[0];
    }

    HttpResponse<String> previa(String corpo) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest
                .newBuilder(URI.create("http://localhost:" + porta + "/api/admin/prompts/" + ID + "/preview/"))
                .header("Cookie", cookies).header("X-CSRFToken", csrf).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(corpo)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 529 e uma chamada so (sem retry), 500 com a mensagem e sem "code". */
    @Test
    void erroDaAnthropicVira500ComMensagemENaoRetenta() throws Exception {
        ANTHROPIC.preparar(JSON.readTree("""
                [{"status": 529, "corpo": {"type": "error", "error": {"type": "overloaded_error", "message": "Overloaded"}}},
                 {"status": 529, "corpo": {"type": "error", "error": {"type": "overloaded_error", "message": "Overloaded"}}}]"""));
        HttpResponse<String> r = previa("{\"pedido_exemplo\": {}}");
        assertThat(r.statusCode()).isEqualTo(500);
        var corpo = JSON.readTree(r.body());
        assertThat(corpo.propertyNames()).containsExactly("error");
        assertThat(corpo.get("error").propertyNames()).containsExactly("message");
        assertThat(corpo.get("error").get("message").asString()).startsWith("Erro ao gerar preview: ")
                .contains("529");
        ANTHROPIC.conferir(JSON.readTree("""
                [{"metodo": "POST", "caminho": "/v1/messages", "chave": "sk-ant-previa", "versao": "2023-06-01",
                  "beta": null, "corpo": {"model": "claude-sonnet-4-6", "max_tokens": 1500,
                  "system": [{"type": "text", "text": "Sistema.", "cache_control": {"type": "ephemeral"}}],
                  "messages": [{"role": "user", "content": "\\n\\nNome: Maria\\nSexo: feminino\\nIdade: não informada\\n\\nPedido: Oração de teste"}]}}]"""),
                "PreviaDePromptTest");
    }

    @Test
    void valorEmBrancoOuNaoTextoDaErroAninhadoPorChave() throws Exception {
        ANTHROPIC.preparar(JSON.readTree("[]"));
        HttpResponse<String> r = previa("{\"pedido_exemplo\": {\"nome\": \"  \", \"sexo\": true, \"idade\": 4}}");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(r.body())).isEqualTo(JSON.readTree("""
                {"pedido_exemplo": {"nome": ["Este campo pode não estar em branco."], "sexo": ["Não é uma string válida."]}}"""));
    }
}
