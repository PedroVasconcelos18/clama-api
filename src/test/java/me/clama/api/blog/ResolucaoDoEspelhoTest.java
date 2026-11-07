package me.clama.api.blog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import me.clama.api.BancoDeTeste;
import me.clama.api.blog.wordpress.ClienteWordPress;
import me.clama.api.blog.wordpress.PostWp;
import me.clama.api.blog.wordpress.WordPressIndisponivel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {"ANTHROPIC_API_KEY=", "MERCADOPAGO_ACCESS_TOKEN=test",
        "MERCADOPAGO_WEBHOOK_SECRET=x", "TURNSTILE_SECRET_KEY="})
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class ResolucaoDoEspelhoTest {
    @Autowired ResolucaoDoEspelho resolucao;
    @Autowired PostEspelhoRepository espelhos;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager tm;
    @MockitoBean ClienteWordPress wordpress;

    @BeforeEach
    void limpa() {
        BancoDeTeste.exigirContainer(jdbc);
        jdbc.execute("TRUNCATE blog_postespelho CASCADE");
        when(wordpress.configurado()).thenReturn(true);
    }

    @AfterEach
    void limpaDepois() {
        BancoDeTeste.exigirContainer(jdbc);
        jdbc.execute("TRUNCATE blog_postespelho CASCADE");
    }

    static PostWp post(long id, String slug, String status, boolean senha) {
        return new PostWp(id, slug, "Titulo", "Titulo", status, "2026-11-19T12:00:00", "https://clama.me/blog/" + slug,
                senha);
    }

    private void espelho(int wp, String slug, String status, String publishedAt, String createdAt) {
        jdbc.update("insert into blog_postespelho (id, wp_post_id, slug, titulo, status, published_at, url, created_at,"
                + " updated_at) values (gen_random_uuid(), ?, ?, 't', ?, ?::timestamptz, '', ?::timestamptz, now())",
                wp, slug, status, publishedAt, createdAt);
    }

    @Test
    void criaPublicadoComOsCamposDoWebhook() {
        when(wordpress.buscarPorSlug("novo")).thenReturn(Optional.of(post(201, "novo", "publish", false)));
        PostEspelho e = resolucao.resolver("novo");
        assertThat(e.getWpPostId()).isEqualTo(201);
        assertThat(e.getStatus()).isEqualTo("publicado");
        assertThat(e.getPublishedAt()).hasToString("2026-11-19T12:00Z");
        assertThat(e.getUrl()).isEqualTo("https://clama.me/blog/novo");
        assertThat(espelhos.findBySlug("novo")).hasSize(1);
    }

    @Test
    void espelhoExistenteNaoVaiAoWordPress() {
        espelho(1, "velho", "publicado", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z");
        assertThat(resolucao.resolver("velho").getWpPostId()).isEqualTo(1);
        verify(wordpress, never()).buscarPorSlug(anyString());
    }

    @Test
    void espelhoSobreviveAo409EAoRollbackDeQuemChamou() {
        when(wordpress.buscarPorSlug("rasc")).thenReturn(Optional.of(post(7, "rasc", "draft", false)));
        TransactionTemplate externa = new TransactionTemplate(tm);
        assertThatThrownBy(() -> externa.executeWithoutResult(s -> resolucao.resolver("rasc")))
                .isInstanceOfSatisfying(PostNaoAceitaInteracao.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.corpo()).hasToString("{error={code=post_nao_aceita_interacao, message=Post não está "
                            + "publicado., pastoral_message=Este texto saiu do ar enquanto você escrevia. Copiamos o "
                            + "que você digitou — guarde, e tente de novo quando ele voltar.}}");
                });
        assertThat(espelhos.findByWpPostId(7)).isPresent().get().extracting(PostEspelho::getStatus)
                .isEqualTo("rascunho");
    }

    @Test
    void protegidoPorSenhaVira409ComEspelhoPrivado() {
        when(wordpress.buscarPorSlug("sec")).thenReturn(Optional.of(post(8, "sec", "publish", true)));
        assertThatThrownBy(() -> resolucao.resolver("sec")).isInstanceOf(PostNaoAceitaInteracao.class);
        assertThat(espelhos.findByWpPostId(8).orElseThrow().getStatus()).isEqualTo("privado");
    }

    @Test
    void wordpressForaDoArNaoViraNaoEncontrado() {
        when(wordpress.buscarPorSlug("x")).thenThrow(new WordPressIndisponivel(null));
        assertThatThrownBy(() -> resolucao.resolver("x")).isInstanceOf(WordPressIndisponivel.class);
        assertThat(espelhos.findBySlug("x")).isEmpty();
    }

    @Test
    void wordpressSemOPostEh404() {
        when(wordpress.buscarPorSlug("x")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> resolucao.resolver("x")).isInstanceOfSatisfying(PostNaoEncontrado.class, e -> {
            assertThat(e.status()).isEqualTo(404);
            assertThat(e.corpo()).hasToString("{error={code=post_nao_encontrado, message=Post não encontrado., "
                    + "pastoral_message=Não encontramos este texto. Ele pode ter saído do ar.}}");
        });
    }

    @Test
    void naoConfiguradoNaoChamaRede() {
        when(wordpress.configurado()).thenReturn(false);
        assertThatThrownBy(() -> resolucao.resolver("x")).isInstanceOf(PostNaoEncontrado.class);
        verify(wordpress, never()).buscarPorSlug(anyString());
    }

    @Test
    void ordemNullsFirst() {
        espelho(1, "dup", "publicado", "2026-05-01T00:00:00Z", "2026-05-01T00:00:00Z");
        espelho(2, "dup", "rascunho", null, "2026-01-01T00:00:00Z");
        espelho(3, "dup", "publicado", "2026-02-01T00:00:00Z", "2026-06-01T00:00:00Z");
        // sem published_at (NULL primeiro no DESC) vence o publicado mais recente: 409, nao o 200 do publicado
        assertThatThrownBy(() -> resolucao.resolver("dup")).isInstanceOf(PostNaoAceitaInteracao.class);
        assertThat(espelhos.findFirstBySlugOrderByPublishedAtDescCreatedAtDesc("dup").orElseThrow().getWpPostId())
                .isEqualTo(2);
        jdbc.update("delete from blog_postespelho where wp_post_id = 2");
        assertThat(resolucao.resolver("dup").getWpPostId()).isEqualTo(1); // published_at maior
    }

    @Test
    void slugRenomeadoNaoAtualizaALinhaVelha() {
        espelho(5, "antigo", "publicado", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z");
        when(wordpress.buscarPorSlug("novo")).thenReturn(Optional.of(post(5, "novo", "publish", false)));
        assertThat(resolucao.resolver("novo").getSlug()).isEqualTo("antigo");
        assertThat(jdbc.queryForObject("select count(*) from blog_postespelho", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select slug from blog_postespelho", String.class)).isEqualTo("antigo");
    }

    @Test
    void camposCortadosEFallbacks() {
        String longo = "é".repeat(250);
        when(wordpress.buscarPorSlug("s")).thenReturn(Optional.of(new PostWp(9, "", null, longo, null, "lixo",
                "u".repeat(600), false)));
        assertThatThrownBy(() -> resolucao.resolver("s")).isInstanceOf(PostNaoAceitaInteracao.class);
        PostEspelho e = espelhos.findByWpPostId(9).orElseThrow();
        assertThat(e.getSlug()).isEqualTo("s");
        assertThat(e.getTitulo()).isEqualTo("é".repeat(200));
        assertThat(e.getUrl()).hasSize(500);
        assertThat(e.getPublishedAt()).isNull();
        assertThat(e.getStatus()).isEqualTo("rascunho");
    }

    @Test
    void corridaNaoDuplica() throws Exception {
        CountDownLatch ambosPerguntaram = new CountDownLatch(2);
        when(wordpress.buscarPorSlug("novo")).thenAnswer(i -> {
            ambosPerguntaram.countDown();
            ambosPerguntaram.await();
            return Optional.of(post(42, "novo", "publish", false));
        });
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<PostEspelho> chamada = () -> resolucao.resolver("novo");
            List<Future<PostEspelho>> fs = pool.invokeAll(List.of(chamada, chamada));
            assertThat(fs.get(0).get().getId()).isEqualTo(fs.get(1).get().getId());
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from blog_postespelho", Integer.class)).isEqualTo(1);
    }
}
