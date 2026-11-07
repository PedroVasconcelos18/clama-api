package me.clama.api.oracao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import me.clama.api.BancoDeTeste;
import me.clama.api.catalogo.plano.Plano;
import me.clama.api.catalogo.plano.PlanoRepository;
import me.clama.api.comum.email.CaixaDeSaidaEmMemoria;
import me.clama.api.comum.email.Email;
import me.clama.api.comum.fila.SchedulerLigado;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.token.ServicoDeTokens;
import me.clama.api.pedido.Pedido;
import me.clama.api.pedido.PedidoRepository;
import me.clama.api.pedido.StatusPedido;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/** POST reenviar -> reenviar-oracao com o db-scheduler de verdade: chave de e-mail nova a cada reenvio. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "FRONTEND_URL=https://clama.me", "clama.oracao.enviar-espera=100ms"})
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
@SchedulerLigado
class JobReenviarOracaoTest {
    static final String ORACAO = "Senhor, acolhe a Maria.\n\nAmém.";
    static final String UUID_RE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Value("${local.server.port}")
    int porta;
    @Autowired JdbcTemplate jdbc;
    @Autowired UsuarioRepository usuarios;
    @Autowired PedidoRepository pedidos;
    @Autowired PlanoRepository planos;
    @Autowired ServicoDeTokens tokens;
    @Autowired CaixaDeSaidaEmMemoria caixa;

    final HttpClient http = HttpClient.newHttpClient();
    String cookies;
    String csrf;

    @BeforeEach
    void admin() throws Exception {
        BancoDeTeste.exigirContainer(jdbc);
        jdbc.execute("TRUNCATE scheduled_tasks, orders_pedido, plans_plan, users_user CASCADE");
        caixa.limpar();
        String access = tokens.emitirPar(usuario("admin-reenvio@exemplo.com", true)).access();
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri("/api/csrf/")).build(),
                HttpResponse.BodyHandlers.ofString());
        csrf = new ObjectMapper().readTree(r.body()).get("csrf_token").asString();
        cookies = "clama_access=" + access + "; csrftoken=" + csrf;
    }

    private Usuario usuario(String email, boolean admin) {
        Usuario u = new Usuario();
        u.setEmail(email);
        u.setPassword("x");
        u.setNomeCompleto("Ana Souza");
        u.setClamaAdmin(admin);
        u.setActive(true);
        return usuarios.saveAndFlush(u);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + porta + path);
    }

    private Pedido pedido() {
        Plano plano = new Plano();
        plano.setNome("Pedido de Oração");
        plano.setValorCentavos(2000);
        plano.setDescricao("");
        planos.save(plano);
        Pedido p = new Pedido();
        p.setNome("Maria Silva");
        p.setEmail("maria@exemplo.com");
        p.setPlano(plano);
        p.setValorCentavos(3000);
        p.setStatus(StatusPedido.ENVIADA);
        p.setOracaoGerada(ORACAO);
        return p;
    }

    private void reenviar(UUID id) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri("/api/admin/pedidos/" + id + "/reenviar/"))
                .POST(HttpRequest.BodyPublishers.noBody()).header("Cookie", cookies).header("X-CSRFToken", csrf)
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("{\"status\":\"ok\",\"message\":\"Reenvio disparado\"}");
    }

    /** Enviada de novo e o job ja saiu da fila. */
    private void aguardarEnviada(UUID id, int emails) {
        await().atMost(Duration.ofSeconds(10)).until(() -> caixa.enviados().size() == emails
                && pedidos.findById(id).orElseThrow().getStatus() == StatusPedido.ENVIADA
                && jdbc.queryForObject("select count(*) from scheduled_tasks", Integer.class) == 0);
    }

    /** Dois reenvios em sequencia: dois e-mails, cada um com a sua chave (a Resend nao devolve o anterior). */
    @Test
    void reenviarUsaChaveNova() throws Exception {
        UUID id = pedidos.saveAndFlush(pedido()).getId();
        reenviar(id);
        aguardarEnviada(id, 1);
        reenviar(id);
        aguardarEnviada(id, 2);

        assertThat(caixa.enviados()).extracting(Email::chaveDeIdempotencia).doesNotHaveDuplicates()
                .allSatisfy(c -> assertThat(c).matches("oracao/" + id + "/reenvio/" + UUID_RE));
        assertThat(caixa.enviados()).allSatisfy(e -> {
            assertThat(e.para()).isEqualTo("maria@exemplo.com");
            assertThat(e.texto()).contains(ORACAO);
        });
    }

    /** Freemium ja enviado (oracao_email_sent_at preenchido): no Django e no-op; aqui o reenvio envia. */
    @Test
    void reenviarFreemiumJaEnviadoEnvia() throws Exception {
        Usuario ana = usuario("Ana@Exemplo.com", false);
        Pedido p = pedido();
        p.setEhGratuito(true);
        p.setUser(ana);
        OffsetDateTime antes = OffsetDateTime.parse("2026-11-01T12:00:00Z");
        p.setOracaoEmailSentAt(antes);
        UUID id = pedidos.saveAndFlush(p).getId();

        reenviar(id);
        aguardarEnviada(id, 1);

        Email e = caixa.enviados().get(0);
        assertThat(e.chaveDeIdempotencia()).matches("oracao-freemium/" + id + "/reenvio/" + UUID_RE);
        assertThat(e.para()).isEqualTo("maria@exemplo.com");
        assertThat(e.assunto()).isEqualTo("Sua oração chegou, Maria");
        assertThat(e.texto()).contains(ORACAO);
        assertThat(pedidos.findById(id).orElseThrow().getOracaoEmailSentAt()).isAfter(antes);
    }
}
