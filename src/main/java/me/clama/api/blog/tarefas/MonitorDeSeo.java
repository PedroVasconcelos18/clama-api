package me.clama.api.blog.tarefas;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import me.clama.api.blog.tarefas.GetPublico.Resposta;
import me.clama.api.comum.agenda.ModoDeExecucao;
import me.clama.api.comum.agenda.TarefaAgendada;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * monitorar_seo_do_dominio (blog/tasks.py:699-887): robots.txt, sitemap.xml, noindex no /blog e a chave do IndexNow
 * (so com INDEXNOW_KEY). GET de 10 s que segue redirect, sem retry. Com problema, um evento error so no Sentry com todos.
 * So le: LISTAR e EXECUTAR fazem o mesmo.
 *
 * <p>Detalhe de status do sitemap e da chave: HTTP &gt;= 400 vira "sem resposta" (a Response do requests e falsa), o
 * resto "HTTP n"; robots e /blog dizem sempre "HTTP n". Rede: o nome da classe da excecao do requests.
 */
@Component
public class MonitorDeSeo implements TarefaAgendada {
    private static final Logger LOG = LoggerFactory.getLogger(MonitorDeSeo.class);
    public static final String NOME = "monitorar-seo";
    static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final String base;
    private final String chave;
    private final HttpClient http = GetPublico.cliente(TIMEOUT, HttpClient.Redirect.ALWAYS);

    MonitorDeSeo(@Value("${FRONTEND_PUBLIC_BLOG_BASE_URL:https://clama.me}") String base,
            @Value("${INDEXNOW_KEY:}") String chave) {
        this.base = base.replaceAll("/+$", "");
        this.chave = chave;
    }

    @Override
    public String nome() {
        return NOME;
    }

    @Override
    public boolean alteraDados() {
        return false;
    }

    /** (resposta, erro) do _buscar: um dos dois e nulo. */
    private record Busca(Resposta r, String erro) {
        /** erro or ("HTTP n" if resposta else "sem resposta") */
        String detalhe() {
            return erro != null ? erro : r.status() < 400 ? "HTTP " + r.status() : "sem resposta";
        }
    }

    @Override
    public ResultadoDaTarefa executar(ModoDeExecucao modo) {
        List<Map<String, String>> problemas = new ArrayList<>();
        var r = new LinkedHashMap<String, Object>();
        r.put("base", base);
        r.put("problemas", problemas);
        r.put("verificacoes", 0);
        if (base.isEmpty()) {
            problemas.add(problema("configuracao", "FRONTEND_PUBLIC_BLOG_BASE_URL vazio"));
            return new ResultadoDaTarefa(NOME, modo, r);
        }
        int verificacoes = 3;

        Busca robots = buscar("/robots.txt");
        if (robots.erro() != null) {
            problemas.add(problema("robots_indisponivel", robots.erro()));
        } else if (robots.r().status() != 200) {
            problemas.add(problema("robots_indisponivel", "HTTP " + robots.r().status()));
        } else if (robots.r().texto().lines()
                .anyMatch(l -> l.strip().replace(" ", "").toLowerCase(Locale.ROOT).equals("disallow:/"))) {
            // a linha exata: "Disallow: /admin/" contem "Disallow: /" como substring
            problemas.add(problema("robots_bloqueia_tudo",
                    "robots.txt contém 'Disallow: /' — o domínio inteiro sai do índice"));
        }

        Busca sitemap = buscar("/sitemap.xml");
        if (sitemap.erro() != null || sitemap.r().status() != 200) {
            problemas.add(problema("sitemap_indisponivel", sitemap.detalhe()));
        }

        Busca blog = buscar("/blog");
        if (blog.erro() != null) {
            problemas.add(problema("blog_indisponivel", blog.erro()));
        } else if (blog.r().status() != 200) {
            problemas.add(problema("blog_indisponivel", "HTTP " + blog.r().status()));
        } else {
            String cabecalho = blog.r().header("X-Robots-Tag");
            if (cabecalho.toLowerCase(Locale.ROOT).contains("noindex")) {
                problemas.add(problema("noindex_no_header", "X-Robots-Tag: " + cabecalho));
            }
            String corpo = blog.r().texto().toLowerCase(Locale.ROOT);
            if (corpo.contains("name=\"robots\"") && corpo.contains("noindex")) {
                problemas.add(problema("noindex_na_meta", "meta robots com noindex na listagem do blog"));
            }
        }

        if (!chave.isEmpty()) { // sem chave nao ha o que vigiar
            verificacoes++;
            Busca arquivo = buscar("/" + chave + ".txt");
            if (arquivo.erro() != null || arquivo.r().status() != 200) {
                problemas.add(problema("chave_indexnow_inacessivel",
                        arquivo.detalhe() + " — submissões ao Bing param sem aviso"));
            } else if (!arquivo.r().texto().strip().equals(chave)) {
                problemas.add(problema("chave_indexnow_divergente", "o arquivo na raiz não contém a chave configurada"));
            }
        }
        r.put("verificacoes", verificacoes);

        if (problemas.isEmpty()) {
            LOG.info("seo_do_dominio_ok {}", r);
        } else {
            // uma mensagem por execucao: quatro alertas para uma causa so seriam ruido
            LOG.error("seo_do_dominio_com_problema {}", r);
            Sentry.captureMessage("SEO do domínio comprometido — " + problemas.stream()
                    .map(p -> p.get("tipo") + ": " + p.get("detalhe")).collect(Collectors.joining("; ")),
                    SentryLevel.ERROR);
        }
        return new ResultadoDaTarefa(NOME, modo, r);
    }

    private Busca buscar(String caminho) {
        try {
            return new Busca(GetPublico.get(http, base + caminho, TIMEOUT, true), null);
        } catch (IOException e) {
            return new Busca(null, GetPublico.nomeDoErro(e));
        }
    }

    private static Map<String, String> problema(String tipo, String detalhe) {
        var p = new LinkedHashMap<String, String>();
        p.put("tipo", tipo);
        p.put("detalhe", detalhe);
        return p;
    }
}
