package me.clama.api.blog.tarefas;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import me.clama.api.comum.agenda.ModoDeExecucao;
import me.clama.api.comum.agenda.TarefaAgendada;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * verificar_urls_indexadas (blog/tasks.py:907-998): {base}/blog e {base}/blog/{slug} de cada espelho publicado (ordem
 * de slug) respondem 200 direto. Nao segue redirect (allow_redirects=False): 3xx e o que se quer medir. 15 s de
 * timeout. divergencia_do_espelho e o contador da reconciliacao. So le: LISTAR e EXECUTAR fazem o mesmo.
 */
@Component
public class VerificacaoDeUrls implements TarefaAgendada {
    private static final Logger LOG = LoggerFactory.getLogger(VerificacaoDeUrls.class);
    public static final String NOME = "verificar-urls";
    static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final JdbcTemplate jdbc;
    private final ContadorDeDivergencia contador;
    private final String base;
    private final HttpClient http = GetPublico.cliente(TIMEOUT, HttpClient.Redirect.NEVER);

    VerificacaoDeUrls(JdbcTemplate jdbc, ContadorDeDivergencia contador,
            @Value("${FRONTEND_PUBLIC_BLOG_BASE_URL:https://clama.me}") String base) {
        this.jdbc = jdbc;
        this.contador = contador;
        this.base = base.replaceAll("/+$", "");
    }

    @Override
    public String nome() {
        return NOME;
    }

    @Override
    public boolean alteraDados() {
        return false;
    }

    @Override
    public ResultadoDaTarefa executar(ModoDeExecucao modo) {
        List<Map<String, Object>> com404 = new ArrayList<>();
        List<Map<String, Object>> com301 = new ArrayList<>();
        List<Map<String, Object>> outros = new ArrayList<>();
        var r = new LinkedHashMap<String, Object>();
        r.put("verificadas", 0);
        r.put("ok", 0);
        r.put("com_404", com404);
        r.put("com_301", com301);
        r.put("outros", outros);
        r.put("divergencia_do_espelho", 0);
        if (base.isEmpty()) {
            return new ResultadoDaTarefa(NOME, modo, r);
        }

        List<String> urls = new ArrayList<>();
        urls.add(base + "/blog");
        jdbc.queryForList("select slug from blog_postespelho where status = 'publicado' order by slug", String.class)
                .forEach(slug -> urls.add(base + "/blog/" + slug));

        int ok = 0;
        for (String url : urls) {
            int codigo;
            String para;
            try {
                GetPublico.Resposta resposta = GetPublico.get(http, url, TIMEOUT, false);
                codigo = resposta.status();
                para = resposta.headers().firstValue("Location").orElse("");
            } catch (IOException e) {
                outros.add(item("url", url, "erro", GetPublico.nomeDoErro(e)));
                continue;
            }
            if (codigo == 200) {
                ok++;
            } else if (codigo == 404) {
                com404.add(item("url", url));
            } else if (codigo >= 300 && codigo < 400) {
                com301.add(item("url", url, "status", codigo, "para", para));
            } else {
                outros.add(item("url", url, "status", codigo));
            }
        }
        r.put("verificadas", urls.size());
        r.put("ok", ok);
        r.put("divergencia_do_espelho", contador.valor());

        if (com404.isEmpty() && com301.isEmpty() && outros.isEmpty()) {
            LOG.info("urls_indexadas_ok {}", r);
        } else {
            LOG.error("urls_indexadas_com_regressao {}", r);
            // nomeia ate dez: "12 URLs com problema" manda procurar do zero
            String exemplos = Stream.of(com404, com301, outros).flatMap(List::stream).limit(10)
                    .map(i -> (String) i.get("url")).collect(Collectors.joining(", "));
            Sentry.captureMessage("Regressão em URL indexada — " + com404.size() + " com 404, " + com301.size()
                    + " com redirect inesperado. " + exemplos, SentryLevel.ERROR);
        }
        return new ResultadoDaTarefa(NOME, modo, r);
    }

    /** Chave, valor, ... na ordem do dict do Django. */
    private static Map<String, Object> item(Object... kv) {
        var m = new LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
