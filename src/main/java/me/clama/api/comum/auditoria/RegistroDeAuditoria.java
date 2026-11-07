package me.clama.api.comum.auditoria;

import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Grava django_admin_log (LogEntry do Django) para o Django /admin continuar mostrando o historico. Roda na transacao
 * do chamador: rollback leva a linha junto. objetoRepr leva o id, nunca e-mail (o Django gravaria str(obj)).
 * mudanca vira o change_message JSON do ModelAdmin: [{"added": {}}], [{"changed": {"fields": [...]}}]; null (exclusao)
 * grava "", como o log_deletion.
 */
@Component
public class RegistroDeAuditoria {
    public static final int ADICAO = 1;
    public static final int MUDANCA = 2;
    public static final int EXCLUSAO = 3;
    private static final Logger LOG = LoggerFactory.getLogger(RegistroDeAuditoria.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final Clock relogio;

    RegistroDeAuditoria(JdbcTemplate jdbc, Clock relogio) {
        this.jdbc = jdbc;
        this.relogio = relogio;
    }

    public void registrar(long autorId, String appLabel, String model, String objetoId, String objetoRepr, int acao,
            Object mudanca) {
        String mensagem = mudanca == null ? "" : JSON.writeValueAsString(mudanca);
        String repr = objetoRepr.length() > 200 ? objetoRepr.substring(0, 200) : objetoRepr; // varchar(200)
        jdbc.update("insert into django_admin_log (action_time, object_id, object_repr, action_flag, change_message, "
                + "content_type_id, user_id) values (?, ?, ?, ?, ?, (select id from django_content_type "
                + "where app_label = ? and model = ?), ?)", OffsetDateTime.now(relogio), objetoId, repr, acao, mensagem,
                appLabel, model, autorId);
        LOG.info("auditoria autor={} objeto={}.{}#{} acao={}", autorId, appLabel, model, objetoId, acao);
    }
}
