package me.clama.api.blog.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.sentry.Sentry;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import me.clama.api.BancoDeTeste;
import me.clama.api.RelogioDeTeste;
import me.clama.api.comum.observabilidade.TransportEmMemoria;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** O job chamado direto (sem o agendador); o replay do Django esta no ContratoBlogTest. */
@SpringBootTest(properties = {"ANTHROPIC_API_KEY="})
@Import({BancoDeTeste.class, RelogioDeTeste.class})
@ActiveProfiles("test")
class JobSincronizarEspelhoTest {
    static final Instant AGORA = Instant.parse("2026-11-20T15:00:00Z");

    @Autowired
    JobSincronizarEspelho job;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RelogioDeTeste.Fixavel relogio;

    @BeforeEach
    void limpar() {
        BancoDeTeste.exigirContainer(jdbc);
        jdbc.execute("TRUNCATE payments_webhookevento, blog_reacao, blog_comentario, blog_postespelho CASCADE");
        relogio.fixar(AGORA);
    }

    @AfterEach
    void soltar() {
        relogio.fixar(null);
    }

    private UUID evento(String status, String tipo, String payload) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO payments_webhookevento (created_at, updated_at, id, provider, external_event_id,"
                + " event_type, payload, status, error_message) VALUES (now(), now(), ?, 'WORDPRESS', ?, ?, ?::jsonb,"
                + " ?, '')", id, "wp-" + id, tipo, payload, status);
        return id;
    }

    private String status(UUID id) {
        return jdbc.queryForObject("SELECT status || '/' || error_message FROM payments_webhookevento WHERE id = ?",
                String.class, id);
    }

    private String espelho(int wpPostId) {
        return jdbc.queryForObject("SELECT slug || '|' || titulo || '|' || status || '|'"
                + " || coalesce((published_at AT TIME ZONE 'UTC')::text, '-') || '|' || url FROM blog_postespelho"
                + " WHERE wp_post_id = ?", String.class, wpPostId);
    }

    /**
     * ERRO reprocessa (so PROCESSADO/IGNORADO sao terminais). Data fora do calendario faz o job falhar sem mexer no
     * evento (a fila retenta); esgotadas as retentativas, o evento vai para ERRO, so o status.
     */
    @Test
    void erroReprocessavel() throws Exception {
        assertThat(job.maxRetentativas()).isEqualTo(3);
        assertThat(job.esperaEntreTentativas()).isEqualTo(Duration.ofSeconds(30));

        UUID erro = evento("ERRO", "post_publicado",
                "{\"wp_post_id\": \"5\", \"slug\": \"s\", \"status\": \"publish\"}");
        job.executar(erro, 0);
        assertThat(status(erro)).isEqualTo("PROCESSADO/");
        assertThat(espelho(5)).isEqualTo("s||publicado|-|");

        UUID ruim = evento("RECEBIDO", "post_publicado", "{\"wp_post_id\": \"6\", \"published_at\": "
                + "\"2026-13-40T10:00:00\"}");
        assertThatThrownBy(() -> job.executar(ruim, 0)).isInstanceOf(DateTimeException.class);
        assertThat(status(ruim)).isEqualTo("RECEBIDO/");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM blog_postespelho WHERE wp_post_id = 6", Integer.class))
                .isZero();
        job.esgotou(ruim, new DateTimeException("x"));
        assertThat(status(ruim)).isEqualTo("ERRO/");

        UUID feito = evento("PROCESSADO", "post_publicado", "{\"wp_post_id\": \"7\"}");
        job.esgotou(feito, null); // terminal nao volta
        assertThat(status(feito)).isEqualTo("PROCESSADO/");
        job.esgotou(UUID.randomUUID(), null); // evento inexistente: nada
    }

    /**
     * O que pode lancar (data, wp_post_id fora do integer) vem antes da traducao do status: o alerta de status
     * desconhecido sai uma vez, na tentativa que aplica, e nao a cada retentativa que falha.
     */
    @Test
    void statusDesconhecidoNaoAlertaEmTentativaQueFalha() throws Exception {
        TransportEmMemoria sentry = new TransportEmMemoria();
        Sentry.init(o -> {
            o.setDsn("https://chave@localhost/1");
            o.setTransportFactory(sentry.fabrica());
        });
        try {
            UUID data = evento("RECEBIDO", "post_publicado", "{\"wp_post_id\": 8, \"status\": \"rascunhinho\","
                    + " \"published_at\": \"2026-13-40T10:00:00\"}");
            assertThatThrownBy(() -> job.executar(data, 0)).isInstanceOf(DateTimeException.class);
            UUID enorme = evento("RECEBIDO", "post_publicado", "{\"wp_post_id\": 99999999999, \"status\": \"x\"}");
            assertThatThrownBy(() -> job.executar(enorme, 0)).isInstanceOf(ArithmeticException.class);
            assertThat(sentry.eventos).isEmpty();

            job.executar(evento("RECEBIDO", "post_publicado", "{\"wp_post_id\": 8, \"status\": \"rascunhinho\"}"), 0);
            assertThat(sentry.eventos).extracting(e -> e.getMessage().getFormatted())
                    .containsExactly("Status desconhecido do WordPress: 'rascunhinho'");
        } finally {
            Sentry.close();
        }
    }

    /** int() do Python: JSON 12.7 vira 12, "12.0" falha (ERRO e return), espacos, sinal, "_" e bool passam. */
    @Test
    void wpPostIdComoOIntDoPython() throws Exception {
        String[][] casos = {{"12.7", "12"}, {"\" +13 \"", "13"}, {"\"1_400\"", "1400"}, {"true", "1"}};
        for (String[] c : casos) {
            UUID id = evento("RECEBIDO", "post_atualizado", "{\"wp_post_id\": " + c[0] + ", \"status\": \"draft\"}");
            job.executar(id, 0);
            assertThat(status(id)).as(c[0]).isEqualTo("PROCESSADO/");
            assertThat(espelho(Integer.parseInt(c[1]))).as(c[0]).isEqualTo("||rascunho|-|");
        }
        for (String invalido : new String[] {"\"12.0\"", "null", "\"\"", "[1]", "\"doze\""}) {
            UUID id = evento("RECEBIDO", "post_atualizado", "{\"wp_post_id\": " + invalido + "}");
            job.executar(id, 0);
            assertThat(status(id)).as(invalido).isEqualTo("ERRO/");
        }
        UUID semChave = evento("RECEBIDO", "post_atualizado", "{}");
        job.executar(semChave, 0);
        assertThat(status(semChave)).isEqualTo("ERRO/");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM blog_postespelho", Integer.class)).isEqualTo(4);
    }

    /** _como_bool, post_removido = lixeira, cortes [:200]/[:500] e update_or_create sem apagar. */
    @Test
    void camposDoEspelho() throws Exception {
        UUID a = evento("RECEBIDO", "post_publicado", "{\"wp_post_id\": 20, \"status\": \"publish\","
                + " \"protegido_por_senha\": \" ON \", \"slug\": \"" + "s".repeat(210) + "\", \"titulo\": \""
                + "é".repeat(201) + "\", \"url\": \""
                + "u".repeat(501) + "\", \"published_at\": \"2026-11-19T12:00:00Z\"}");
        job.executar(a, 0);
        assertThat(espelho(20)).isEqualTo("s".repeat(200) + "|" + "é".repeat(200) + "|privado|2026-11-19 12:00:00|"
                + "u".repeat(500));

        UUID b = evento("RECEBIDO", "post_removido", "{\"wp_post_id\": 20, \"status\": \"publish\", \"slug\": \"novo\","
                + " \"protegido_por_senha\": \"0\"}");
        job.executar(b, 0);
        assertThat(espelho(20)).isEqualTo("novo||lixeira|-|");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM blog_postespelho", Integer.class)).isEqualTo(1);
    }

    /** parse_datetime do Django 4.2: fromisoformat, senao o regex; ingenua em America/Sao_Paulo (R14). */
    @Test
    void publicadoEm() {
        assertThat(JobSincronizarEspelho.publicadoEm("2026-11-19T12:00:00"))
                .isEqualTo(OffsetDateTime.parse("2026-11-19T15:00:00Z"));
        assertThat(JobSincronizarEspelho.publicadoEm("2026-11-19"))
                .isEqualTo(OffsetDateTime.parse("2026-11-19T03:00Z"));
        assertThat(JobSincronizarEspelho.publicadoEm("2026-1-5 1:2"))
                .isEqualTo(OffsetDateTime.parse("2026-01-05T04:02Z"));
        assertThat(JobSincronizarEspelho.publicadoEm("2026-11-19T12:00:00.1234567 -0330").toInstant())
                .isEqualTo(Instant.parse("2026-11-19T15:30:00.123456Z"));
        assertThat(JobSincronizarEspelho.publicadoEm("2026-11-19T12:00:00+05").toInstant())
                .isEqualTo(Instant.parse("2026-11-19T07:00:00Z"));
        assertThat(JobSincronizarEspelho.publicadoEm("")).isNull();
        assertThat(JobSincronizarEspelho.publicadoEm("lixo")).isNull();
        assertThat(JobSincronizarEspelho.publicadoEm("2026-02-30")).as("so data invalida: None").isNull();
        assertThatThrownBy(() -> JobSincronizarEspelho.publicadoEm("2026-11-19T25:00:00"))
                .isInstanceOf(DateTimeException.class);
    }
}
