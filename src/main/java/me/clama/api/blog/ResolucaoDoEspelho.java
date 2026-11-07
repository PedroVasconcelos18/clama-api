package me.clama.api.blog;

import java.util.Optional;
import me.clama.api.blog.wordpress.ClienteWordPress;
import me.clama.api.blog.wordpress.PostWp;
import me.clama.api.blog.wordpress.StatusWordPress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * services/espelho.py:resolver_espelho. Espelho existente nunca e revalidado nem atualizado. O que falta e criado a
 * partir do WordPress: o GET fica fora de qualquer transacao de escrita (R10) e o INSERT roda em REQUIRES_NEW (R3),
 * entao a linha sobrevive ao 409 e ao rollback de quem chamou.
 */
@Component
public class ResolucaoDoEspelho {
    private static final Logger LOG = LoggerFactory.getLogger(ResolucaoDoEspelho.class);

    private final PostEspelhoRepository espelhos;
    private final ClienteWordPress wordpress;
    private final TransactionTemplate novaTransacao;

    public ResolucaoDoEspelho(PostEspelhoRepository espelhos, ClienteWordPress wordpress,
            PlatformTransactionManager tm) {
        this.espelhos = espelhos;
        this.wordpress = wordpress;
        this.novaTransacao = new TransactionTemplate(tm);
        this.novaTransacao.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * @throws PostNaoEncontrado 404: nem o espelho nem o WordPress conhecem o slug (ou nao ha WordPress configurado)
     * @throws PostNaoAceitaInteracao 409: o post existe e nao esta publicado
     * @throws me.clama.api.blog.wordpress.WordPressIndisponivel 400: nao deu para perguntar; nunca vira 404
     */
    public PostEspelho resolver(String slug) {
        PostEspelho espelho = espelhos.findFirstBySlugOrderByPublishedAtDescCreatedAtDesc(slug)
                .orElseGet(() -> criarAPartirDoWordPress(slug));
        if (!espelho.aceitaInteracao()) throw new PostNaoAceitaInteracao();
        return espelho;
    }

    private PostEspelho criarAPartirDoWordPress(String slug) {
        // sem credencial nao existe WordPress neste ambiente: slug sem espelho nao existe, e sem rede
        if (!wordpress.configurado()) throw new PostNaoEncontrado();
        Optional<PostWp> achado = wordpress.buscarPorSlug(slug);
        if (achado.isEmpty()) {
            LOG.info("espelho_slug_inexistente_no_wordpress (slug={})", slug);
            throw new PostNaoEncontrado();
        }
        PostWp post = achado.get();
        try {
            PostEspelho espelho = novaTransacao.execute(s -> espelhos.findByWpPostId(Math.toIntExact(post.id()))
                    .orElseGet(() -> espelhos.save(novo(post, slug))));
            LOG.info("espelho_resolvido_sob_demanda (slug={}, wp_post_id={})", slug, post.id());
            return espelho;
        } catch (DataIntegrityViolationException corrida) {
            // o webhook ou outro request gravou o mesmo wp_post_id agora: a linha que venceu serve igual
            return espelhos.findByWpPostId(Math.toIntExact(post.id())).orElseThrow(() -> corrida);
        }
    }

    private static PostEspelho novo(PostWp post, String slug) {
        PostEspelho e = new PostEspelho();
        e.setWpPostId(Math.toIntExact(post.id()));
        e.setSlug(cortar(vazioPara(post.slug(), slug), 200));
        e.setTitulo(cortar(vazioPara(post.tituloRaw(), vazioPara(post.tituloRendered(), "")), 200));
        e.setStatus(StatusWordPress.efetivo(post.status() == null ? "" : post.status(), post.protegidoPorSenha()));
        e.setPublishedAt(post.publicadoEm());
        e.setUrl(cortar(post.link() == null ? "" : post.link(), 500));
        return e;
    }

    private static String vazioPara(String valor, String padrao) {
        return valor == null || valor.isEmpty() ? padrao : valor;
    }

    /** [:n] do Python conta code points. */
    public static String cortar(String s, int n) {
        return s.codePointCount(0, s.length()) <= n ? s : s.substring(0, s.offsetByCodePoints(0, n));
    }
}
