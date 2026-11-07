package me.clama.api.blog.tarefas;

import static org.assertj.core.api.Assertions.assertThat;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.clama.api.BancoDeTeste;
import me.clama.api.RelogioDeTeste;
import me.clama.api.comum.agenda.ModoDeExecucao;
import me.clama.api.comum.cripto.CampoCriptografado;
import me.clama.api.comum.email.CaixaDeSaidaEmMemoria;
import me.clama.api.comum.email.Email;
import me.clama.api.comum.observabilidade.TransportEmMemoria;
import me.clama.api.contrato.StubDeIntegracoes;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

/** As cinco tarefas do blog chamadas direto (sem o agendador); o replay do Django esta no ContratoBlogTest. */
@SpringBootTest(properties = {"ANTHROPIC_API_KEY=", "WORDPRESS_API_USER=u", "WORDPRESS_API_APP_PASSWORD=s",
        "ADMIN_ALERT_EMAIL=alertas@clama.me", "INDEXNOW_KEY="})
@Import({BancoDeTeste.class, RelogioDeTeste.class})
@ActiveProfiles("test")
class TarefasDoBlogTest {
    static final Instant AGORA = Instant.parse("2026-11-20T15:00:00Z");
    static final ObjectMapper JSON = new ObjectMapper();
    static final StubDeIntegracoes WORDPRESS = new StubDeIntegracoes("wordpress", Map.of());
    static final StubDeIntegracoes MONITORES = new StubDeIntegracoes("monitores", Map.of());

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry r) {
        r.add("WORDPRESS_API_URL", WORDPRESS::url);
        r.add("FRONTEND_PUBLIC_BLOG_BASE_URL", MONITORES::url);
    }

    @AfterAll
    static void pararStubs() {
        WORDPRESS.parar();
        MONITORES.parar();
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired RelogioDeTeste.Fixavel relogio;
    @Autowired ReconciliacaoDoEspelho reconciliacao;
    @Autowired VerificacaoDeUrls urls;
    @Autowired MonitorDeSeo seo;
    @Autowired AlertaDeComentarios alerta;
    @Autowired PurgaDeIps purga;
    @Autowired ContadorDeDivergencia contador;
    @Autowired CaixaDeSaidaEmMemoria caixa;
    @Autowired UsuarioRepository usuarios;
    final TransportEmMemoria sentry = new TransportEmMemoria();

    @BeforeEach
    void preparar() {
        BancoDeTeste.exigirContainer(jdbc);
        jdbc.execute("TRUNCATE blog_reacao, blog_comentario, blog_customerbanido, blog_postespelho, users_user CASCADE");
        relogio.fixar(AGORA);
        contador.limpar();
        caixa.limpar();
        Sentry.init(o -> {
            o.setDsn("https://chave@localhost/1");
            o.setTransportFactory(sentry.fabrica());
        });
    }

    @AfterEach
    void desligar() {
        Sentry.close();
        relogio.fixar(null);
    }

    // --- dados ------------------------------------------------------------------------------------------------------

    private UUID espelho(int wp, String slug, String status, Instant publicado) {
        UUID id = UUID.randomUUID();
        Timestamp antes = Timestamp.from(AGORA.minus(Duration.ofDays(10)));
        jdbc.update("insert into blog_postespelho (id, created_at, updated_at, wp_post_id, slug, titulo, status,"
                + " published_at, url) values (?, ?, ?, ?, ?, ?, ?, ?, ?)", id, antes, antes, wp, slug, "T " + slug,
                status, publicado == null ? null : Timestamp.from(publicado), "https://clama.me/blog/" + slug);
        return id;
    }

    private long usuario(String email) {
        Usuario u = new Usuario();
        u.setEmail(email);
        u.setPassword("x");
        u.setNomeCompleto("Nome");
        return usuarios.saveAndFlush(u).getId();
    }

    private UUID comentario(long customer, UUID espelho, String conteudo, String ip, Instant criado) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into blog_comentario (id, created_at, updated_at, customer_id, post_espelho_id, conteudo,"
                + " ip_address, is_suspeito) values (?, ?, ?, ?, ?, ?, ?, false)", id, Timestamp.from(criado),
                Timestamp.from(criado), customer, espelho, conteudo, new CampoCriptografado().convertToDatabaseColumn(ip));
        return id;
    }

    private static String post(int id, String slug, String status) {
        return "{\"id\": " + id + ", \"slug\": \"" + slug + "\", \"title\": {\"raw\": \"T " + slug + "\"}, \"status\": \""
                + status + "\", \"date_gmt\": \"2026-11-10T15:00:00\", \"link\": \"https://clama.me/blog/" + slug
                + "\", \"password\": \"\"}";
    }

    private static String pagina(int total, String... posts) {
        return "{\"status\": 200, \"corpo\": [" + String.join(",", posts) + "], \"headers\": {\"X-WP-TotalPages\": \""
                + total + "\"}}";
    }

    private void wordpress(String... respostas) {
        WORDPRESS.preparar(JSON.readTree("[" + String.join(",", respostas) + "]"));
    }

    private List<Map<String, Object>> espelhos() {
        return jdbc.queryForList("select wp_post_id, slug, status, updated_at from blog_postespelho order by wp_post_id");
    }

    private List<String> mensagens() {
        return sentry.eventos.stream().map(e -> e.getLevel() + " " + e.getMessage().getFormatted()).toList();
    }

    // --- reconciliar-espelho ----------------------------------------------------------------------------------------

    /** Espelho que o WordPress nao lista fica como esta: ausencia nunca e exclusao. */
    @Test
    void reconciliacaoNuncaApaga() {
        espelho(900, "fora-da-lista", "publicado", AGORA.minus(Duration.ofDays(10)));
        List<Map<String, Object>> antes = espelhos();
        wordpress(pagina(1, post(901, "novo", "publish")));

        var r = reconciliacao.executar(ModoDeExecucao.EXECUTAR).resumo();

        assertThat(r).containsEntry("criados", 1).containsEntry("posts_vistos", 1).containsEntry("abortada", false);
        assertThat(espelhos()).hasSize(2).first().isEqualTo(antes.getFirst());
    }

    /** LISTAR calcula o que mudaria e nao grava nada (nem mexe no contador de divergencia). */
    @Test
    void reconciliacaoListarNaoGrava() {
        espelho(102, "rascunho-oculto", "rascunho", null);
        List<Map<String, Object>> antes = espelhos();
        wordpress(pagina(1, post(102, "rascunho-oculto", "publish"), post(105, "novo", "publish")));

        var r = reconciliacao.executar(ModoDeExecucao.LISTAR).resumo();

        assertThat(r).containsEntry("criados", 1).containsEntry("atualizados", 1).containsEntry("rodadas_divergentes", 0);
        assertThat(r.get("divergentes")).isEqualTo(List.of(Map.of("wp_post_id", 102, "slug", "rascunho-oculto",
                "campos", List.of("status", "published_at"))));
        assertThat(espelhos()).isEqualTo(antes);
        assertThat(contador.valor()).isZero();
        assertThat(sentry.eventos).isEmpty();
    }

    /** Pagina 2 indisponivel: a pagina 1 (que divergia) nao e aplicada; um warning exato no Sentry. */
    @Test
    void abortaSemAplicarNada() {
        espelho(102, "rascunho-oculto", "rascunho", null);
        List<Map<String, Object>> antes = espelhos();
        wordpress(pagina(2, post(102, "rascunho-oculto", "publish"), post(105, "novo", "publish")),
                "{\"status\": 200, \"corpo_texto\": \"<html>Em manutenção</html>\"}");

        var r = reconciliacao.executar(ModoDeExecucao.EXECUTAR).resumo();

        assertThat(r).containsEntry("abortada", true).containsEntry("motivo", "wordpress_indisponivel")
                .containsEntry("paginas_lidas", 1).containsEntry("criados", 0).containsEntry("atualizados", 0);
        assertThat(espelhos()).isEqualTo(antes);
        assertThat(mensagens()).containsExactly("WARNING Reconciliação do espelho abortada: WordPress indisponível");
    }

    /**
     * Duas rodadas seguidas com divergencia alertam; o verificar-urls le o mesmo contador; ele expira 3600 s depois da
     * ultima rodada divergente, e uma rodada sem divergencia o zera.
     */
    @Test
    void divergenciaEmDuasRodadasAlerta() {
        wordpress(pagina(1, post(105, "novo", "publish")), pagina(1, post(105, "novo", "draft")),
                pagina(1, post(105, "novo", "draft")));
        MONITORES.preparar(JSON.readTree("[]"));

        assertThat(reconciliacao.executar(ModoDeExecucao.EXECUTAR).resumo()).containsEntry("rodadas_divergentes", 1);
        assertThat(sentry.eventos).isEmpty();
        assertThat(reconciliacao.executar(ModoDeExecucao.EXECUTAR).resumo()).containsEntry("rodadas_divergentes", 2);
        assertThat(mensagens()).containsExactly("WARNING Espelho divergente do WordPress em 2 rodadas seguidas — 0 "
                + "criados, 1 atualizados. Posts: novo#105(status)");
        assertThat(urls.executar(ModoDeExecucao.EXECUTAR).resumo()).containsEntry("divergencia_do_espelho", 2);

        relogio.fixar(AGORA.plusSeconds(3599));
        assertThat(contador.valor()).isEqualTo(2);
        relogio.fixar(AGORA.plusSeconds(3600));
        assertThat(contador.valor()).isZero();

        relogio.fixar(AGORA);
        assertThat(reconciliacao.executar(ModoDeExecucao.EXECUTAR).resumo()).containsEntry("rodadas_divergentes", 0)
                .containsEntry("sem_mudanca", 1);
        assertThat(contador.valor()).isZero();
    }

    // --- purgar-ips -------------------------------------------------------------------------------------------------

    private String ip(UUID comentario) {
        return new CampoCriptografado().convertToEntityAttribute(
                jdbc.queryForObject("select ip_address from blog_comentario where id = ?", String.class, comentario));
    }

    /** created_at < agora - 180 dias: exatamente 180 dias fica; LISTAR so conta. */
    @Test
    void purgaExatamente180DiasFica() {
        long u = usuario("a@x.com");
        UUID e = espelho(1, "a", "publicado", null);
        UUID limite = comentario(u, e, "limite", "10.0.0.1", AGORA.minus(Duration.ofDays(180)));
        UUID velho = comentario(u, e, "velho", "10.0.0.2", AGORA.minus(Duration.ofDays(180)).minusSeconds(1));
        comentario(u, e, "sem ip", "", AGORA.minus(Duration.ofDays(400)));

        assertThat(purga.executar(ModoDeExecucao.LISTAR).resumo()).isEqualTo(Map.of("n_purgados", 1));
        assertThat(ip(velho)).isEqualTo("10.0.0.2");

        assertThat(purga.executar(ModoDeExecucao.EXECUTAR).resumo()).isEqualTo(Map.of("n_purgados", 1));
        assertThat(ip(velho)).isEmpty();
        assertThat(ip(limite)).isEqualTo("10.0.0.1");
        assertThat(purga.executar(ModoDeExecucao.EXECUTAR).resumo()).isEqualTo(Map.of("n_purgados", 0));
    }

    /** QuerySet.update do Django: so ip_address (cifrado, nunca "" cru), sem tocar em updated_at. */
    @Test
    void purgaNaoMudaUpdatedAt() {
        long u = usuario("a@x.com");
        UUID velho = comentario(u, espelho(1, "a", "publicado", null), "velho", "10.0.0.2",
                AGORA.minus(Duration.ofDays(200)));
        OffsetDateTime antes = jdbc.queryForObject("select updated_at from blog_comentario where id = ?",
                OffsetDateTime.class, velho);

        purga.executar(ModoDeExecucao.EXECUTAR);

        assertThat(jdbc.queryForObject("select updated_at from blog_comentario where id = ?", OffsetDateTime.class,
                velho)).isEqualTo(antes);
        assertThat(jdbc.queryForObject("select ip_address from blog_comentario where id = ?", String.class, velho))
                .startsWith("gAAAAA");
        assertThat(ip(velho)).isEmpty();
    }

    // --- alerta-comentarios -----------------------------------------------------------------------------------------

    /** R4: comentario so com o espelho (post legado nulo) entra no e-mail com o slug do espelho. */
    @Test
    void alertaComComentarioSoNoEspelho() {
        long u = usuario("ana@x.com");
        UUID e = espelho(1, "salmo-23", "publicado", null);
        comentario(u, e, "x".repeat(101), "", AGORA.minus(Duration.ofHours(1)));

        assertThat(alerta.executar(ModoDeExecucao.EXECUTAR).resumo())
                .isEqualTo(Map.of("n_novos", 1, "n_suspeitos", 0, "n_email_enviados", 1));
        Email email = caixa.enviados().getFirst();
        assertThat(email.para()).isEqualTo("alertas@clama.me");
        assertThat(email.assunto()).isEqualTo("Resumo de comentários do blog (1 novos, 0 suspeitos)");
        assertThat(email.texto()).isEqualTo("Resumo das últimas 24h\n\n- /blog/salmo-23 | ana@x.com: " + "x".repeat(100)
                + "…\n\nModere em: /admin/blog/comentarios");
        assertThat(email.html()).isNull();
    }

    // --- monitores --------------------------------------------------------------------------------------------------

    /** Tres checagens com problema: um evento so, nivel error, com todos os problemas. */
    @Test
    void monitorUmSentryPorExecucao() {
        MONITORES.preparar(JSON.readTree("[{\"status\": 200, \"corpo_texto\": \"User-agent: *\\nDisallow: /\\n\"},"
                + " {\"status\": 404, \"corpo_texto\": \"\"}, {\"status\": 302, \"corpo_texto\": \"\", \"headers\":"
                + " {\"Location\": \"/blog/\"}}, {\"status\": 500, \"corpo_texto\": \"\"}]"));

        var r = seo.executar(ModoDeExecucao.EXECUTAR).resumo();

        assertThat(r.get("verificacoes")).isEqualTo(3);
        assertThat(sentry.eventos).hasSize(1);
        assertThat(sentry.eventos.getFirst().getLevel()).isEqualTo(SentryLevel.ERROR);
        // o /blog seguiu o 302 (requests segue redirect) e caiu no 500
        assertThat(sentry.eventos.getFirst().getMessage().getFormatted()).isEqualTo("SEO do domínio comprometido — "
                + "robots_bloqueia_tudo: robots.txt contém 'Disallow: /' — o domínio inteiro sai do índice; "
                + "sitemap_indisponivel: sem resposta; blog_indisponivel: HTTP 500");
    }

    /** allow_redirects=False: o 301 e o resultado, com o Location; o destino nunca e pedido. */
    @Test
    void urlsNaoSegueRedirect() {
        espelho(1, "salmo-23", "publicado", null);
        espelho(2, "rascunho", "rascunho", null);
        MONITORES.preparar(JSON.readTree("[{\"status\": 200, \"corpo_texto\": \"\"}, {\"status\": 301, \"corpo_texto\":"
                + " \"\", \"headers\": {\"Location\": \"/blog/salmo-23/\"}}]"));

        var r = urls.executar(ModoDeExecucao.EXECUTAR).resumo();

        assertThat(r.get("com_301")).isEqualTo(List.of(Map.of("url", MONITORES.url() + "/blog/salmo-23", "status", 301,
                "para", "/blog/salmo-23/")));
        assertThat(r).containsEntry("verificadas", 2).containsEntry("ok", 1);
        MONITORES.conferir(JSON.readTree("[{\"metodo\": \"GET\", \"caminho\": \"/blog\"}, {\"metodo\": \"GET\","
                + " \"caminho\": \"/blog/salmo-23\"}]"), "urlsNaoSegueRedirect");
        assertThat(mensagens()).containsExactly("ERROR Regressão em URL indexada — 0 com 404, 1 com redirect inesperado. "
                + MONITORES.url() + "/blog/salmo-23");
    }
}
