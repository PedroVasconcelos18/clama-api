package me.clama.api.blog.tarefas;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import me.clama.api.blog.ResolucaoDoEspelho;
import me.clama.api.comum.agenda.ModoDeExecucao;
import me.clama.api.comum.agenda.TarefaAgendada;
import me.clama.api.comum.email.Email;
import me.clama.api.comum.email.EnviadorDeEmail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * enviar_alerta_comentarios_diario (blog/tasks.py:201-272): resumo em texto dos comentarios das ultimas 24 h para
 * ADMIN_ALERT_EMAIL, suspeitos primeiro. Zero comentarios ou destinatario vazio: sem e-mail. Falha no envio propaga
 * (fail_silently=False), sem retry.
 *
 * <p>R4: a linha usa o slug do espelho. O Django le c.post.slug, que e nulo em todo comentario feito no espelho, e a
 * task quebraria todo dia (nunca rodou). LISTAR monta tudo e nao envia (n_email_enviados 0).
 */
@Component
public class AlertaDeComentarios implements TarefaAgendada {
    private static final Logger LOG = LoggerFactory.getLogger(AlertaDeComentarios.class);
    public static final String NOME = "alerta-comentarios";
    static final Duration JANELA = Duration.ofHours(24);

    private final JdbcTemplate jdbc;
    private final EnviadorDeEmail enviador;
    private final Clock relogio;
    private final String adminEmail;

    AlertaDeComentarios(JdbcTemplate jdbc, EnviadorDeEmail enviador, Clock relogio,
            @Value("${ADMIN_ALERT_EMAIL:contato@clama.me}") String adminEmail) {
        this.jdbc = jdbc;
        this.enviador = enviador;
        this.relogio = relogio;
        this.adminEmail = adminEmail;
    }

    @Override
    public String nome() {
        return NOME;
    }

    @Override
    public boolean alteraDados() {
        return false;
    }

    private record Novo(boolean suspeito, String conteudo, String email, String slug) {}

    @Override
    public ResultadoDaTarefa executar(ModoDeExecucao modo) {
        List<Novo> novos = jdbc.query("""
                select c.is_suspeito, c.conteudo, u.email, e.slug from blog_comentario c
                join users_user u on u.id = c.customer_id
                left join blog_postespelho e on e.id = c.post_espelho_id
                where c.created_at >= ? order by c.is_suspeito desc, c.created_at desc""",
                (rs, i) -> new Novo(rs.getBoolean(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                Timestamp.from(relogio.instant().minus(JANELA)));
        int suspeitos = (int) novos.stream().filter(Novo::suspeito).count();
        var r = new LinkedHashMap<String, Object>();
        r.put("n_novos", novos.size());
        r.put("n_suspeitos", suspeitos);
        r.put("n_email_enviados", 0);

        if (novos.isEmpty()) {
            LOG.info("alerta_comentarios_diario_skip");
            return new ResultadoDaTarefa(NOME, modo, r);
        }
        if (adminEmail.isEmpty()) {
            LOG.warn("admin_alert_email_missing (event=alerta_comentarios_diario_no_recipient)");
            return new ResultadoDaTarefa(NOME, modo, r);
        }

        StringBuilder corpo = new StringBuilder("Resumo das últimas ").append(JANELA.toHours()).append("h\n\n");
        for (Novo c : novos) {
            String snippet = ResolucaoDoEspelho.cortar(c.conteudo(), 100)
                    + (c.conteudo().codePointCount(0, c.conteudo().length()) > 100 ? "…" : "");
            corpo.append("- /blog/").append(c.slug()).append(" | ").append(c.email())
                    .append(c.suspeito() ? " [SUSPEITO]" : "").append(": ").append(snippet).append('\n');
        }
        corpo.append("\nModere em: /admin/blog/comentarios");
        Email email = new Email(adminEmail, "Resumo de comentários do blog (" + novos.size() + " novos, " + suspeitos
                + " suspeitos)", corpo.toString(), null);

        if (modo != ModoDeExecucao.EXECUTAR) {
            LOG.info("alerta_comentarios_diario (modo LISTAR, nada enviado): n_novos={} n_suspeitos={}", novos.size(),
                    suspeitos);
            return new ResultadoDaTarefa(NOME, modo, r);
        }
        enviador.enviar(email);
        LOG.info("alerta_comentarios_diario_sent (n_novos={}, n_suspeitos={})", novos.size(), suspeitos);
        r.put("n_email_enviados", 1);
        return new ResultadoDaTarefa(NOME, modo, r);
    }
}
