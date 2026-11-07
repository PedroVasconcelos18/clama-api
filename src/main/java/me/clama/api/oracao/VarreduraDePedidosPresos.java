package me.clama.api.oracao;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import me.clama.api.comum.agenda.ModoDeExecucao;
import me.clama.api.comum.agenda.TarefaAgendada;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Rede de seguranca nova (sem equivalente no Django, onde os handlers de retry mortos deixam pedidos parados): pedido
 * num status do pipeline, sem mexer ha mais que a janela e sem job na fila, vai para erro com alerta. Nunca regera.
 */
@Component
public class VarreduraDePedidosPresos implements TarefaAgendada {
    private static final Logger LOG = LoggerFactory.getLogger(VarreduraDePedidosPresos.class);
    public static final String NOME = "pedidos-presos";
    static final String ERRO = "pedido_preso";

    // igualdade exata em (task_name, task_instance), nunca LIKE; o updated_at volta como texto para nao perder micros
    private static final String PRESOS = """
            select p.id, p.status, p.updated_at::text from orders_pedido p
            where p.status in ('pago','gerando_oracao','oracao_gerada','aguardando_reenvio')
              and p.updated_at < now() - make_interval(secs => ?)
              and not exists (select 1 from scheduled_tasks t where
                   (t.task_name = 'gerar-oracao' and t.task_instance = 'gerar-oracao/' || p.id)
                or (t.task_name = 'enviar-oracao' and t.task_instance = 'enviar-oracao/' || p.id)
                or (t.task_name = 'reenviar-oracao' and t.task_instance = 'reenviar-oracao/' || p.id))
            order by p.updated_at""";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AlertaDeErro alerta;
    private final Duration janela;
    /** Gancho de teste: roda entre a leitura e o UPDATE condicional. */
    Runnable aposLeitura = () -> { };

    VarreduraDePedidosPresos(JdbcTemplate jdbc, TransactionTemplate tx, AlertaDeErro alerta,
            @Value("${clama.agenda.pedidos-presos.janela:PT20M}") Duration janela) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.alerta = alerta;
        this.janela = janela;
    }

    @Override
    public String nome() {
        return NOME;
    }

    @Override
    public boolean alteraDados() {
        return true;
    }

    private record Preso(UUID id, String status, String updatedAt) {
    }

    @Override
    public ResultadoDaTarefa executar(ModoDeExecucao modo) {
        List<Preso> presos = jdbc.query(PRESOS,
                (rs, i) -> new Preso(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)),
                janela.toMillis() / 1000.0);
        aposLeitura.run();
        int marcados = 0;
        if (modo == ModoDeExecucao.EXECUTAR) {
            for (Preso p : presos) {
                Boolean ok = tx.execute(s -> {
                    int n = jdbc.update("""
                            update orders_pedido set status = 'erro', last_error = ?, updated_at = now()
                            where id = ? and status = ? and updated_at = ?::timestamptz""",
                            ERRO, p.id(), p.status(), p.updatedAt());
                    if (n == 1) alerta.registrar(p.id());
                    return n == 1;
                });
                if (Boolean.TRUE.equals(ok)) marcados++;
            }
        } else {
            List<UUID> ids = new ArrayList<>();
            presos.forEach(p -> ids.add(p.id()));
            LOG.info("Pedidos presos (modo LISTAR, nada alterado): {}", ids);
        }
        var resumo = new LinkedHashMap<String, Object>();
        resumo.put("presos", presos.size());
        resumo.put("marcados", marcados);
        return new ResultadoDaTarefa(NOME, modo, resumo);
    }
}
