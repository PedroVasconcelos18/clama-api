package me.clama.api.blog.tarefas;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import me.clama.api.blog.PostEspelho;
import me.clama.api.blog.PostEspelhoRepository;
import me.clama.api.blog.ResolucaoDoEspelho;
import me.clama.api.blog.wordpress.ClienteWordPress;
import me.clama.api.blog.wordpress.PaginaWp;
import me.clama.api.blog.wordpress.PostWp;
import me.clama.api.blog.wordpress.StatusWordPress;
import me.clama.api.blog.wordpress.WordPressIndisponivel;
import me.clama.api.comum.agenda.ModoDeExecucao;
import me.clama.api.comum.agenda.TarefaAgendada;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * reconciliar_espelho_com_wordpress (blog/tasks.py:442-664): le a listagem inteira do WordPress (100 por pagina, ate
 * 50 paginas) e corrige o espelho. Nunca apaga, nunca conclui exclusao por ausencia. WordPress indisponivel em qualquer
 * pagina ou listagem acima do teto: aborta sem aplicar nada. Cada linha na sua transacao (os autocommits do Django).
 *
 * <p>LISTAR (o modo inicial, R2): le e compara igual, devolve o mesmo resumo com o que EXECUTAR faria (criados,
 * atualizados, sem_mudanca, divergentes) e nao grava nada; tambem nao mexe no contador de divergencia nem alerta
 * divergencia persistente (nada e corrigido, ela persistiria para sempre), e rodadas_divergentes e o valor atual do
 * contador. Os avisos de abortada saem nos dois modos. O log diz quantos espelhos mudariam de status (checklist da
 * Etapa 9).
 */
@Component
public class ReconciliacaoDoEspelho implements TarefaAgendada {
    private static final Logger LOG = LoggerFactory.getLogger(ReconciliacaoDoEspelho.class);
    public static final String NOME = "reconciliar-espelho";
    static final int MAX_PAGINAS = 50;
    static final int RODADAS_PARA_ALERTAR = 2;

    private final ClienteWordPress wordpress;
    private final PostEspelhoRepository espelhos;
    private final ContadorDeDivergencia contador;
    private final TransactionTemplate tx;

    ReconciliacaoDoEspelho(ClienteWordPress wordpress, PostEspelhoRepository espelhos, ContadorDeDivergencia contador,
            TransactionTemplate tx) {
        this.wordpress = wordpress;
        this.espelhos = espelhos;
        this.contador = contador;
        this.tx = tx;
    }

    @Override
    public String nome() {
        return NOME;
    }

    @Override
    public boolean alteraDados() {
        return true;
    }

    /** Os 5 campos do espelho, na ordem do dict "desejado" (a ordem de "campos" no resumo). */
    private record Desejado(String slug, String titulo, String status, OffsetDateTime publishedAt, String url) {
        static Desejado de(PostWp p) {
            return new Desejado(ResolucaoDoEspelho.cortar(ouVazio(p.slug()), 200),
                    ResolucaoDoEspelho.cortar(ouVazio(p.tituloRaw()).isEmpty() ? ouVazio(p.tituloRendered())
                            : p.tituloRaw(), 200),
                    StatusWordPress.efetivo(ouVazio(p.status()), p.protegidoPorSenha()), p.publicadoEm(),
                    ResolucaoDoEspelho.cortar(ouVazio(p.link()), 500));
        }

        List<String> divergentes(PostEspelho e) {
            List<String> campos = new ArrayList<>();
            if (!slug.equals(e.getSlug())) campos.add("slug");
            if (!titulo.equals(e.getTitulo())) campos.add("titulo");
            if (!status.equals(e.getStatus())) campos.add("status");
            // datetime aware do Python compara o instante
            if (publishedAt == null ? e.getPublishedAt() != null
                    : e.getPublishedAt() == null || !publishedAt.isEqual(e.getPublishedAt())) campos.add("published_at");
            if (!url.equals(e.getUrl())) campos.add("url");
            return campos;
        }

        PostEspelho aplicar(PostEspelho e) {
            e.setSlug(slug);
            e.setTitulo(titulo);
            e.setStatus(status);
            e.setPublishedAt(publishedAt);
            e.setUrl(url);
            return e;
        }
    }

    @Override
    public ResultadoDaTarefa executar(ModoDeExecucao modo) {
        boolean executar = modo == ModoDeExecucao.EXECUTAR;
        var r = new LinkedHashMap<String, Object>();
        r.put("paginas_lidas", 0);
        r.put("posts_vistos", 0);
        r.put("criados", 0);
        r.put("atualizados", 0);
        r.put("sem_mudanca", 0);
        List<Map<String, Object>> divergentes = new ArrayList<>();
        r.put("divergentes", divergentes);
        r.put("rodadas_divergentes", 0);
        r.put("abortada", false);
        r.put("motivo", "");

        if (!wordpress.configurado()) {
            LOG.warn("reconciliacao_abortada (motivo=credencial_ausente)");
            return abortada(r, modo, "credencial_ausente");
        }

        List<PostWp> vistos = new ArrayList<>();
        int semId = 0;
        int paginas = 0;
        for (int pagina = 1; ; pagina++) {
            if (pagina > MAX_PAGINAS) {
                // saiu pelo teto: a listagem esta incompleta e nao se sabe o que ficou de fora
                LOG.warn("reconciliacao_abortada (motivo=listagem_incompleta, paginas_lidas={})", paginas);
                Sentry.captureMessage("Reconciliação do espelho abortada: listagem incompleta", SentryLevel.WARNING);
                return abortada(r, modo, "listagem_incompleta");
            }
            PaginaWp lida;
            try {
                lida = wordpress.listar(pagina);
            } catch (WordPressIndisponivel e) {
                // aplicar o que ja foi lido deixaria o espelho num estado que ninguem descreve
                LOG.warn("reconciliacao_abortada (motivo=wordpress_indisponivel, pagina={})", pagina);
                Sentry.captureMessage("Reconciliação do espelho abortada: WordPress indisponível", SentryLevel.WARNING);
                return abortada(r, modo, "wordpress_indisponivel");
            }
            vistos.addAll(lida.posts());
            semId += lida.semId();
            r.put("paginas_lidas", ++paginas);
            if (pagina >= lida.totalPaginas()) break;
        }
        r.put("posts_vistos", vistos.size() + semId);

        int criados = 0;
        int atualizados = 0;
        int semMudanca = 0;
        int mudariamDeStatus = 0;
        List<String> identificacao = new ArrayList<>();
        for (PostWp post : vistos) {
            int wp = Math.toIntExact(post.id());
            Desejado d = Desejado.de(post);
            PostEspelho e = espelhos.findByWpPostId(wp).orElse(null);
            if (e == null) {
                if (executar) {
                    PostEspelho novo = new PostEspelho();
                    novo.setWpPostId(wp);
                    tx.executeWithoutResult(s -> espelhos.save(d.aplicar(novo)));
                }
                criados++;
                continue;
            }
            List<String> campos = d.divergentes(e);
            if (campos.isEmpty()) {
                // nao grava: mexeria no updated_at de tudo a cada 15 minutos
                semMudanca++;
                continue;
            }
            var item = new LinkedHashMap<String, Object>();
            item.put("wp_post_id", wp);
            item.put("slug", d.slug());
            item.put("campos", campos);
            divergentes.add(item);
            identificacao.add(d.slug() + "#" + wp + "(" + String.join("/", campos) + ")");
            if (campos.contains("status")) mudariamDeStatus++;
            if (executar) {
                tx.executeWithoutResult(s -> espelhos.save(d.aplicar(e)));
            }
            atualizados++;
        }
        r.put("criados", criados);
        r.put("atualizados", atualizados);
        r.put("sem_mudanca", semMudanca);

        if (executar) {
            avaliarDivergencia(r, divergentes, identificacao, criados, atualizados);
            LOG.info("reconciliacao_concluida {}", r);
        } else {
            r.put("rodadas_divergentes", contador.valor());
            LOG.info("reconciliacao_listar (modo LISTAR, nada gravado): criariam={} atualizariam={} "
                    + "mudariam_de_status={} divergentes={}", criados, atualizados, mudariamDeStatus, divergentes);
        }
        return new ResultadoDaTarefa(NOME, modo, r);
    }

    /** _avaliar_divergencia: criado tambem e divergencia (evento de webhook perdido). */
    private void avaliarDivergencia(Map<String, Object> r, List<Map<String, Object>> divergentes,
            List<String> identificacao, int criados, int atualizados) {
        if (divergentes.isEmpty() && criados == 0) {
            contador.limpar();
            r.put("rodadas_divergentes", 0);
            return;
        }
        int rodadas = contador.somar();
        r.put("rodadas_divergentes", rodadas);
        if (rodadas < RODADAS_PARA_ALERTAR) {
            LOG.info("reconciliacao_divergencia_pontual (rodadas={}, criados={}, atualizados={})", rodadas, criados,
                    atualizados);
            return;
        }
        String posts = identificacao.isEmpty() ? "sem campo divergente; posts ausentes foram criados"
                : String.join(", ", identificacao.subList(0, Math.min(10, identificacao.size())));
        LOG.warn("reconciliacao_divergencia_persistente (rodadas={}, criados={}, atualizados={}, divergentes={})",
                rodadas, criados, atualizados, divergentes.stream().limit(10).toList());
        Sentry.captureMessage("Espelho divergente do WordPress em " + rodadas + " rodadas seguidas — " + criados
                + " criados, " + atualizados + " atualizados. Posts: " + posts, SentryLevel.WARNING);
    }

    private static ResultadoDaTarefa abortada(Map<String, Object> r, ModoDeExecucao modo, String motivo) {
        r.put("abortada", true);
        r.put("motivo", motivo);
        return new ResultadoDaTarefa(NOME, modo, r);
    }

    private static String ouVazio(String s) {
        return Objects.requireNonNullElse(s, "");
    }
}
