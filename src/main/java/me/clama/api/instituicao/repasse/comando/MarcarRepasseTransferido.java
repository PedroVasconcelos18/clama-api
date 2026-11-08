package me.clama.api.instituicao.repasse.comando;

import static me.clama.api.comum.texto.TextoPython.repr;

import java.sql.Array;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.clama.api.comum.auditoria.RegistroDeAuditoria;
import me.clama.api.comum.comando.Chamada;
import me.clama.api.comum.comando.Comando;
import me.clama.api.comum.comando.Comandos.Opcao;
import me.clama.api.comum.comando.ErroDeComando;
import me.clama.api.instituicao.repasse.comando.RelatorioRepasse.Alvo;
import me.clama.api.instituicao.repasse.comando.RelatorioRepasse.Periodo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * marcar_repasse_transferido (mapa §4): quita os repasses pendentes do recorte que o relatorio emitiu (a mesma
 * resolucao de instituicao e periodo). Simula por padrao. Corrigido no Java: data da lista em Sao Paulo (D14); UPDATE
 * condicional com conferencia da contagem, sem sobrescrever quitacao concorrente, e comprovante ate 120 (D11);
 * --autor &lt;id&gt; grava o django_admin_log na mesma transacao. O log estruturado sai sempre.
 */
@Component
public class MarcarRepasseTransferido implements Comando {
    public static final List<Opcao> OPCOES = List.of(Opcao.obrigatoria("--instituicao"), Opcao.valor("--mes"),
            Opcao.valor("--desde"), Opcao.valor("--ate"), Opcao.valor("--comprovante"), Opcao.flag("--confirmar"),
            Opcao.valor("--autor"));
    static final int COMPROVANTE_MAX = 120; // varchar(120)
    private static final Logger LOG = LoggerFactory.getLogger(MarcarRepasseTransferido.class);

    private final RelatorioRepasse relatorio;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RegistroDeAuditoria auditoria;
    private final Clock relogio;

    MarcarRepasseTransferido(RelatorioRepasse relatorio, JdbcTemplate jdbc, TransactionTemplate tx,
            RegistroDeAuditoria auditoria, Clock relogio) {
        this.relatorio = relatorio;
        this.jdbc = jdbc;
        this.tx = tx;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    record Pendente(UUID id, UUID pedido, OffsetDateTime criado, int valor) {}

    @Override
    public int executar(Chamada c) throws Exception {
        Long autor = auditoria.autorDoComando(c.valor("--autor"));
        Alvo instituicao = relatorio.resolverInstituicao(c.valor("--instituicao"));
        Periodo periodo = relatorio.resolverPeriodo(c);

        StringBuilder sql = new StringBuilder("select id, pedido_id, created_at, valor_centavos from "
                + "instituicoes_repasse where instituicao_id = ? and status = 'registrado'");
        List<Object> args = new ArrayList<>(List.of(instituicao.id()));
        if (periodo.desde() != null) {
            sql.append(" and created_at >= ?");
            args.add(RelatorioRepasse.inicio(periodo.desde()));
        }
        if (periodo.ate() != null) {
            sql.append(" and created_at < ?");
            args.add(RelatorioRepasse.depoisDoFim(periodo.ate()));
        }
        List<Pendente> pendentes = jdbc.query(sql + " order by created_at, id", (rs, n) -> new Pendente(
                rs.getObject("id", UUID.class), rs.getObject("pedido_id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class), rs.getInt("valor_centavos")), args.toArray());

        if (pendentes.isEmpty()) {
            c.out().println("Nenhum repasse pendente para " + instituicao.nome() + " no período.");
            return 0;
        }
        long total = pendentes.stream().mapToLong(Pendente::valor).sum();
        c.out().println();
        c.out().println("QUITAÇÃO — " + instituicao.nome());
        for (Pendente p : pendentes) {
            c.out().println("  " + RelatorioRepasse.BR.format(RelatorioRepasse.diaLocal(p.criado())) + "  "
                    + RelatorioRepasse.direita(RelatorioRepasse.reais(p.valor()), 10) + "  pedido " + p.pedido());
        }
        c.out().println();
        c.out().println(pendentes.size() + " repasse(s), total " + RelatorioRepasse.reais(total));

        if (!c.tem("--confirmar")) {
            c.out().println();
            c.out().println("SIMULAÇÃO — nada foi gravado. Repita com --confirmar (e --comprovante) para quitar.");
            return 0;
        }
        String comprovante = c.valor("--comprovante");
        if (comprovante == null || comprovante.isEmpty()) {
            throw new ErroDeComando("--comprovante é obrigatório com --confirmar: sem a referência da transferência "
                    + "a quitação não é auditável.");
        }
        int tamanho = comprovante.codePointCount(0, comprovante.length());
        if (tamanho > COMPROVANTE_MAX) {
            throw new ErroDeComando("--comprovante tem " + tamanho + " caracteres; o máximo é " + COMPROVANTE_MAX
                    + ". Nada foi gravado.");
        }

        quitar(pendentes, comprovante, autor);
        LOG.info("Repasses quitados: instituicao={} quantidade={} total_centavos={} comprovante={} autor={}",
                instituicao.id(), pendentes.size(), total, comprovante, autor);
        c.out().println();
        c.out().println(pendentes.size() + " repasse(s) quitado(s), " + RelatorioRepasse.reais(total)
                + ", comprovante " + repr(comprovante) + ".");
        return 0;
    }

    /**
     * Uma transacao: so quita o que ainda esta registrado; se outra quitacao levou algum no meio do caminho, a
     * contagem nao bate e nada e gravado (o Django sobrescrevia transferido_em e comprovante sem erro).
     */
    private void quitar(List<Pendente> pendentes, String comprovante, Long autor) {
        UUID[] ids = pendentes.stream().map(Pendente::id).toArray(UUID[]::new);
        OffsetDateTime agora = OffsetDateTime.now(relogio);
        tx.executeWithoutResult(st -> {
            int quitados = jdbc.update("""
                    update instituicoes_repasse set status = 'transferido', transferido_em = ?, comprovante = ?,
                        updated_at = ?
                    where id = any(?) and status = 'registrado'""", agora, comprovante, agora, uuids(ids));
            if (quitados != ids.length) {
                throw new ErroDeComando((ids.length - quitados) + " de " + ids.length + " repasse(s) já não estavam "
                        + "pendentes (outra quitação ao mesmo tempo?). Nada foi gravado; rode de novo para ver o que "
                        + "falta.");
            }
            if (autor != null) {
                for (UUID id : ids) {
                    auditoria.registrar(autor, "instituicoes", "repasse", id.toString(), id.toString(),
                            RegistroDeAuditoria.MUDANCA,
                            List.of(Map.of("changed", Map.of("fields", List.of("Status", "Transferido em",
                                    "Comprovante")))));
                }
            }
        });
    }

    private Array uuids(UUID[] ids) {
        return jdbc.execute((ConnectionCallback<Array>) con -> con.createArrayOf("uuid", ids));
    }
}
