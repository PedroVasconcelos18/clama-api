package me.clama.api.pedido.comando;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.io.IOException;
import java.sql.Array;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import me.clama.api.comum.Dinheiro;
import me.clama.api.comum.auditoria.RegistroDeAuditoria;
import me.clama.api.comum.comando.Chamada;
import me.clama.api.comum.comando.Comando;
import me.clama.api.comum.comando.Comandos.Opcao;
import me.clama.api.comum.comando.ErroDeComando;
import me.clama.api.comum.texto.TextoPython;
import me.clama.api.identidade.ExclusaoDeUsuario;
import me.clama.api.pedido.ExclusaoDePedido;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * lgpd_delete (mapa §8.4, D1-D3): dry-run por padrao; --confirm pede "DELETAR" no stdin. Numa transacao: as contas da
 * mesma caixa saem pelo ExclusaoDeUsuario (com as guardas: ultimo admin, banido_por, autor de post; nao ha "si
 * mesmo" no comando), e os pedidos pelo ExclusaoDePedido (com repasse = anonimizado). Guarda que falha = exit 1 e nada
 * muda. Corrigido sobre o Django (que nunca achava nada): busca canonica, e-mail fora do log, do Sentry e do stdout
 * (mascarado), contagens sem a cascata. A blacklist do freemium fica (D4). --autor grava o django_admin_log.
 */
@Component
public class LgpdDelete implements Comando {
    public static final List<Opcao> OPCOES = List.of(Opcao.obrigatoria("--email"), Opcao.flag("--confirm"),
            Opcao.valor("--autor"));
    private static final Logger LOG = LoggerFactory.getLogger(LgpdDelete.class);
    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    /** O que o anonimizar muda, para o change_message da auditoria. */
    private static final List<String> ANONIMIZADOS = List.of("nome", "email", "telefone", "cpf_cnpj",
            "pedido_oracao", "oracao_gerada", "consent_ip", "device_hash", "idade", "sexo", "last_error", "user");

    private final BuscaDoTitular busca;
    private final ExclusaoDeUsuario exclusaoDeUsuario;
    private final ExclusaoDePedido exclusaoDePedido;
    private final RegistroDeAuditoria auditoria;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    LgpdDelete(BuscaDoTitular busca, ExclusaoDeUsuario exclusaoDeUsuario, ExclusaoDePedido exclusaoDePedido,
            RegistroDeAuditoria auditoria, JdbcTemplate jdbc, TransactionTemplate tx) {
        this.busca = busca;
        this.exclusaoDeUsuario = exclusaoDeUsuario;
        this.exclusaoDePedido = exclusaoDePedido;
        this.auditoria = auditoria;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    record Linha(UUID id, OffsetDateTime criado, String status, int valor, boolean repasse) {}

    @Override
    public int executar(Chamada c) throws IOException {
        Long autor = auditoria.autorDoComando(c.valor("--autor"));
        String email = TextoPython.strip(c.valor("--email")).toLowerCase(Locale.ROOT);
        String mascarado = BuscaDoTitular.mascarar(email);
        c.out().println("Buscando pedidos para: " + mascarado);
        BuscaDoTitular.Titular t = busca.buscar(email);
        if (t.vazio()) {
            c.out().println("Nenhum pedido nem conta encontrados para " + mascarado);
            return 0;
        }
        if (autor != null && t.contas().contains(autor)) {
            throw new ErroDeComando("--autor é uma das contas que seriam excluídas. Use o id de outro admin.");
        }

        List<Linha> linhas = jdbc.query("""
                select p.id, p.created_at, p.status, p.valor_centavos,
                    exists (select 1 from instituicoes_repasse r where r.pedido_id = p.id) repasse
                from orders_pedido p where p.id = any(?) order by p.created_at desc, p.id""",
                (rs, n) -> new Linha(rs.getObject("id", UUID.class), rs.getObject("created_at", OffsetDateTime.class),
                        rs.getString("status"), rs.getInt("valor_centavos"), rs.getBoolean("repasse")),
                array("uuid", t.pedidos().toArray()));
        long comRepasse = linhas.stream().filter(Linha::repasse).count();
        c.out().println("Encontrados " + linhas.size() + " pedido(s) e " + t.contas().size() + " conta(s):");
        for (Linha l : linhas) {
            c.out().println("  - " + l.id() + " | " + l.criado().atZoneSameInstant(FUSO).toLocalDate() + " | "
                    + l.status() + " | " + Dinheiro.centavosParaReaisStr(l.valor())
                    + (l.repasse() ? " | repasse: anonimiza" : ""));
        }
        for (long conta : t.contas()) {
            c.out().println("  conta " + conta + " (comentários: " + contar("blog_comentario", conta) + ", reações: "
                    + contar("blog_reacao", conta) + ")");
        }

        if (!c.tem("--confirm")) {
            c.out().println();
            c.out().println("MODO DRY RUN: Nenhum dado foi deletado.");
            c.out().println("Execute com --confirm para deletar permanentemente.");
            return 0;
        }
        c.out().println();
        c.out().println("ATENÇÃO: Você está prestes a DELETAR PERMANENTEMENTE " + (linhas.size() - comRepasse)
                + " pedido(s), anonimizar " + comRepasse + " pedido(s) com repasse e excluir " + t.contas().size()
                + " conta(s).");
        c.out().print("Digite 'DELETAR' para confirmar: ");
        c.out().flush();
        String resposta = c.in().readLine();
        if (resposta == null) {
            c.out().println();
            throw new ErroDeComando("Sem resposta no stdin para a confirmação. Nada foi deletado.");
        }
        if (!resposta.equals("DELETAR")) {
            c.out().println("Operação cancelada.");
            return 0;
        }

        // ids coletados antes: o excluir() solta o user_id dos pedidos da conta
        int[] contas = {0};
        ExclusaoDePedido.Resultado r = tx.execute(st -> {
            for (long conta : t.contas()) {
                contas[0] += exclusaoDeUsuario.excluir(conta) ? 1 : 0;
            }
            ExclusaoDePedido.Resultado res = exclusaoDePedido.excluir(t.pedidos());
            if (autor != null) {
                res.apagados().forEach(id -> auditoria.registrar(autor, "orders", "pedido", id.toString(),
                        id.toString(), RegistroDeAuditoria.EXCLUSAO, null));
                res.anonimizados().forEach(id -> auditoria.registrar(autor, "orders", "pedido", id.toString(),
                        id.toString(), RegistroDeAuditoria.MUDANCA,
                        List.of(Map.of("changed", Map.of("fields", ANONIMIZADOS)))));
                t.contas().forEach(id -> auditoria.registrar(autor, "users", "user", id.toString(), id.toString(),
                        RegistroDeAuditoria.EXCLUSAO, null));
            }
            return res;
        });

        int apagados = r.apagados().size();
        int anonimizados = r.anonimizados().size();
        LOG.info("lgpd_delete executado (pedidos_apagados={}, pedidos_anonimizados={}, contas_excluidas={}, "
                + "usuario_ids={}, autor={})", apagados, anonimizados, contas[0], t.contas(), autor);
        Sentry.captureMessage("LGPD delete: " + apagados + " pedido(s) apagado(s), " + anonimizados
                + " anonimizado(s), " + contas[0] + " conta(s) excluída(s)", SentryLevel.INFO);
        c.out().println();
        c.out().println("Deletados " + apagados + " pedido(s) permanentemente.");
        if (anonimizados > 0) {
            c.out().println("Anonimizados " + anonimizados + " pedido(s) com repasse (o registro contábil fica).");
        }
        if (contas[0] > 0) {
            c.out().println("Excluída(s) " + contas[0] + " conta(s).");
        }
        c.out().println("A ação foi registrada no log e Sentry para fins de auditoria.");
        return 0;
    }

    private int contar(String tabela, long customer) {
        Integer n = jdbc.queryForObject("select count(*) from " + tabela + " where customer_id = ?", Integer.class,
                customer);
        return n == null ? 0 : n;
    }

    private Array array(String tipo, Object[] valores) {
        return jdbc.execute((ConnectionCallback<Array>) con -> con.createArrayOf(tipo, valores));
    }
}
