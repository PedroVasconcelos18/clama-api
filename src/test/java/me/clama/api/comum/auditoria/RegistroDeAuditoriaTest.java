package me.clama.api.comum.auditoria;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import me.clama.api.BancoDeTeste;
import me.clama.api.RelogioDeTeste;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** D8: grava django_admin_log no formato do LogEntry do Django, dentro da transacao do chamador. */
@SpringBootTest
@Import({BancoDeTeste.class, RelogioDeTeste.class})
@ActiveProfiles("test")
class RegistroDeAuditoriaTest {
    @Autowired RegistroDeAuditoria auditoria;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired RelogioDeTeste.Fixavel relogio;

    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void limpar() {
        BancoDeTeste.exigirContainer(jdbc);
        jdbc.update("delete from django_admin_log");
        jdbc.update("delete from django_content_type where app_label = 'users' and model = 'user'");
        jdbc.update("delete from token_blacklist_blacklistedtoken");
        jdbc.update("delete from token_blacklist_outstandingtoken");
        jdbc.update("delete from users_user");
        jdbc.update("insert into users_user (id, password, is_superuser, email, is_staff, is_active, date_joined, "
                + "nome_completo, name, is_clama_admin, force_change_password, nome_format_blog, email_hash, "
                + "sexo) overriding system value values (7, 'x', true, 'a@b.c', true, true, now(), '', '', true, "
                + "false, 'compacto', '', '')");
        relogio.fixar(Instant.parse("2026-11-07T12:00:00Z"));
    }

    @Test
    void gravaLinhaNoFormatoDoDjango() throws Exception {
        jdbc.update("insert into django_content_type (app_label, model) values ('users', 'user')");
        Object mudanca = List.of(Map.of("changed", Map.of("fields", List.of("is_active"))));
        tx.executeWithoutResult(s -> auditoria.registrar(7, "users", "user", "42", "Usuário 42", 2, mudanca));

        Map<String, Object> l = jdbc.queryForMap("select * from django_admin_log");
        assertThat(l.get("user_id")).isEqualTo(7L);
        assertThat(l.get("object_id")).isEqualTo("42");
        assertThat(l.get("object_repr")).isEqualTo("Usuário 42");
        assertThat(((Number) l.get("action_flag")).intValue()).isEqualTo(2);
        assertThat(l.get("content_type_id")).isEqualTo(
                jdbc.queryForObject("select id from django_content_type where model = 'user'", Integer.class));
        assertThat(jdbc.queryForObject("select action_time = '2026-11-07T12:00:00Z' from django_admin_log",
                Boolean.class)).isTrue();
        assertThat(json.readTree((String) l.get("change_message")).toString())
                .isEqualTo("[{\"changed\":{\"fields\":[\"is_active\"]}}]");
    }

    @Test
    void contentTypeAusenteGravaNulo() {
        tx.executeWithoutResult(s -> auditoria.registrar(7, "naoexiste", "nada", "1", "x", 1,
                List.of(Map.of("added", Map.of()))));
        assertThat(jdbc.queryForObject("select content_type_id from django_admin_log", Integer.class)).isNull();
        assertThat(jdbc.queryForObject("select change_message from django_admin_log", String.class))
                .isEqualTo("[{\"added\":{}}]");
    }

    @Test
    void mudancaNulaGravaVazioComoNaExclusaoDoDjango() {
        tx.executeWithoutResult(s -> auditoria.registrar(7, "users", "user", "1", "x", 3, null));
        assertThat(jdbc.queryForObject("select change_message from django_admin_log", String.class)).isEmpty();
    }

    @Test
    void rollbackDoChamadorTiraALinha() {
        try {
            tx.executeWithoutResult(s -> {
                auditoria.registrar(7, "users", "user", "1", "x", 1, List.of());
                throw new IllegalStateException("desfaz");
            });
        } catch (IllegalStateException esperado) {
            // a transacao do chamador foi desfeita
        }
        assertThat(jdbc.queryForObject("select count(*) from django_admin_log", Integer.class)).isZero();
    }
}
