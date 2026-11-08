package me.clama.api.pedido;

import java.sql.Array;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import me.clama.api.comum.cripto.Fernet;
import me.clama.api.freemium.JobConfirmacaoFreemium;
import me.clama.api.oracao.JobAlertaAdmin;
import me.clama.api.oracao.JobEnviarOracao;
import me.clama.api.oracao.JobGerarOracao;
import me.clama.api.oracao.JobReenviarOracao;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * O delete de Pedido do lgpd_delete (mapa 8 §8.4, D2), na transacao do chamador (a mesma do ExclusaoDeUsuario). As FKs
 * sao DEFERRABLE sem ON DELETE: o CASCADE e o SET_NULL do ORM estao aqui, e o PROTECT do Repasse vira anonimizacao
 * (registro contabil de dinheiro repassado a terceiro; LGPD art. 16, I). Ordem: tokens do freemium (CASCADE),
 * webhookevento.pedido (SET_NULL), jobs pendentes do pedido (gerar, enviar, reenviar, alerta-admin, confirmacao-freemium), e o pedido.
 */
@Service
public class ExclusaoDePedido {
    /** Jobs que leem o pedido e mandariam e-mail/oracao/alerta de um pedido anonimizado: nome e chave de instancia. */
    private static final Map<String, Function<UUID, String>> JOBS = Map.of(
            JobGerarOracao.NOME, JobGerarOracao::instancia,
            JobEnviarOracao.NOME, JobEnviarOracao::instancia,
            JobReenviarOracao.NOME, JobReenviarOracao::instancia,
            JobAlertaAdmin.NOME, JobAlertaAdmin::instancia,
            JobConfirmacaoFreemium.NOME, JobConfirmacaoFreemium::instancia);

    private final JdbcTemplate jdbc;
    private final Fernet fernet;
    private final Clock relogio;

    ExclusaoDePedido(JdbcTemplate jdbc, Fernet fernet, Clock relogio) {
        this.jdbc = jdbc;
        this.fernet = fernet;
        this.relogio = relogio;
    }

    /** Os pedidos apagados e os anonimizados (os que tem repasse). */
    public record Resultado(List<UUID> apagados, List<UUID> anonimizados) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public Resultado excluir(Collection<UUID> pedidos) {
        Array ids = array("uuid", pedidos.toArray());
        jdbc.update("delete from freemium_freemiumconfirmationtoken where pedido_id = any(?)", ids);
        jdbc.update("update payments_webhookevento set pedido_id = null where pedido_id = any(?)", ids);
        // scheduled_tasks nasce no 1o deploy do Java (Flyway); antes dele, o comando roda sem a tabela (D6)
        if (Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass('scheduled_tasks') is not null",
                Boolean.class))) {
            Object[] instancias = pedidos.stream().flatMap(p -> JOBS.values().stream().map(j -> j.apply(p))).toArray();
            jdbc.update("delete from scheduled_tasks where task_name = any(?) and task_instance = any(?)",
                    array("text", JOBS.keySet().toArray()), array("text", instancias));
        }
        // o campo cifrado vazio e a cifra de "" (NOT NULL), como o django-encrypted-model-fields grava
        String vazio = fernet.cifrar("");
        List<UUID> anonimizados = jdbc.queryForList("""
                update orders_pedido set nome = ?, email = ?, telefone = ?, cpf_cnpj = ?, pedido_oracao = ?,
                    oracao_gerada = ?, consent_ip = null, device_hash = '', idade = null, sexo = '', last_error = '',
                    user_id = null, updated_at = ?
                where id = any(?) and exists (select 1 from instituicoes_repasse r where r.pedido_id = orders_pedido.id)
                returning id""", UUID.class, vazio, vazio, vazio, vazio, vazio, vazio, OffsetDateTime.now(relogio), ids);
        List<UUID> apagados = jdbc.queryForList("""
                delete from orders_pedido p where id = any(?)
                  and not exists (select 1 from instituicoes_repasse r where r.pedido_id = p.id)
                returning id""", UUID.class, ids);
        return new Resultado(apagados, anonimizados);
    }

    private Array array(String tipo, Object[] valores) {
        return jdbc.execute((ConnectionCallback<Array>) con -> con.createArrayOf(tipo, valores));
    }
}
