package me.clama.api.instituicao.repasse.comando;

import static me.clama.api.comum.texto.TextoPython.repr;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import me.clama.api.comum.comando.Chamada;
import me.clama.api.comum.comando.Comando;
import me.clama.api.comum.comando.Comandos.Opcao;
import me.clama.api.comum.comando.ErroDeComando;
import me.clama.api.instituicao.repasse.comando.RelatorioRepasse.Alvo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * auditar_repasses (services/auditoria.py + o comando; mapa §3): concilia ledger e pedidos, somente leitura. Achado
 * critico = CommandError e exit 1 (o CSV ja gravado); sem achado nao grava o CSV nem com --csv (D8, paridade).
 */
@Component
public class AuditarRepasses implements Comando {
    public static final List<Opcao> OPCOES = List.of(Opcao.valor("--instituicao"), Opcao.valor("--csv"));

    static final String CRITICO = "CRÍTICO";
    static final String ATENCAO = "ATENÇÃO";
    /** STATUS_POS_PAGAMENTO: tudo menos aguardando_pagamento e aguardando_confirmacao_email (ERRO entra). */
    static final Set<String> POS_PAGAMENTO = Set.of("pago", "gerando_oracao", "oracao_gerada", "enviada",
            "aguardando_reenvio", "erro");

    /** TipoAchado, com a gravidade e a EXPLICACAO do comando. */
    enum Tipo {
        ORFAO("orfao", CRITICO, "Pedido pago com instituição escolhida que nunca gerou linha no ledger. "
                + "O repasse é devido e não aparece em relatório nenhum."),
        DIVERGENCIA_DE_BASE("divergencia_de_base", ATENCAO, "O pedido mudou de valor depois do repasse (ex.: "
                + "convertido em gratuito). O ledger está certo; é só ruído esperado."),
        CALCULO_ERRADO("calculo_errado", CRITICO, "O valor gravado não é o percentual do valor base. Ledger "
                + "adulterado ou bug no cálculo."),
        PEDIDO_NAO_PAGO("pedido_nao_pago", CRITICO, "Repasse registrado para pedido que ainda aguarda pagamento — "
                + "cobrança sem dinheiro correspondente."),
        SEM_ID_DE_PAGAMENTO("sem_id_de_pagamento", ATENCAO,
                "Sem o id do Mercado Pago não dá pra bater a linha contra o extrato.");

        final String valor;
        final String gravidade;
        final String explicacao;

        Tipo(String valor, String gravidade, String explicacao) {
            this.valor = valor;
            this.gravidade = gravidade;
            this.explicacao = explicacao;
        }
    }

    record Achado(Tipo tipo, String instituicao, String pedidoId, String detalhe, long valorEmJogo) {
        String gravidade() {
            return tipo.gravidade;
        }
    }

    private final JdbcTemplate jdbc;

    AuditarRepasses(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int executar(Chamada c) throws Exception {
        String termo = c.valor("--instituicao");
        Alvo instituicao = termo == null || termo.isEmpty() ? null : resolverInstituicao(termo);
        List<Achado> achados = auditar(instituicao == null ? null : instituicao.id());

        c.out().println();
        c.out().println("AUDITORIA DE REPASSE — " + (instituicao != null ? instituicao.nome() : "todas as instituições"));
        c.out().println();
        if (achados.isEmpty()) {
            c.out().println("Ledger consistente: nenhum pedido pago sem repasse, nenhum cálculo errado, nenhum "
                    + "repasse sem pagamento.");
            return 0;
        }

        Map<Tipo, List<Achado>> porTipo = new LinkedHashMap<>();
        achados.forEach(a -> porTipo.computeIfAbsent(a.tipo(), t -> new ArrayList<>()).add(a));
        porTipo.forEach((tipo, grupo) -> {
            c.out().println("[" + tipo.gravidade + "] " + tipo.valor + " — " + grupo.size() + " ocorrência(s)");
            c.out().println("  " + tipo.explicacao);
            grupo.forEach(a -> c.out().println("    pedido " + a.pedidoId() + ": " + a.detalhe()));
            c.out().println();
        });
        long emJogo = achados.stream().mapToLong(Achado::valorEmJogo).sum();
        if (emJogo != 0) {
            c.out().println("Total em jogo: " + RelatorioRepasse.reais(emJogo));
        }

        String csv = c.valor("--csv");
        if (csv != null && !csv.isEmpty()) {
            PlanilhaCsv p = new PlanilhaCsv().linha("Gravidade", "Tipo", "Instituição", "ID do pedido", "Detalhe",
                    "Valor em jogo (R$)");
            achados.forEach(a -> p.linha(a.gravidade(), a.tipo().valor, a.instituicao(), a.pedidoId(), a.detalhe(),
                    RelatorioRepasse.planilha(a.valorEmJogo())));
            java.nio.file.Path destino = c.caminho(csv);
            p.gravar(destino);
            c.out().println();
            c.out().println("CSV: " + destino.toRealPath());
        }

        List<Achado> criticos = achados.stream().filter(a -> CRITICO.equals(a.gravidade())).toList();
        if (!criticos.isEmpty()) {
            throw new ErroDeComando(criticos.size() + " achado(s) crítico(s), "
                    + RelatorioRepasse.reais(criticos.stream().mapToLong(Achado::valorEmJogo).sum()) + " em jogo.");
        }
        return 0;
    }

    /** So nome__icontains, sem UUID nem nome exato (difere do relatorio, como no Django). */
    Alvo resolverInstituicao(String termo) {
        List<Alvo> candidatas = RelatorioRepasse.contendo(jdbc, termo);
        if (candidatas.isEmpty()) {
            throw new ErroDeComando("Nenhuma instituição casa com " + repr(termo) + ".");
        }
        if (candidatas.size() > 1) {
            throw new ErroDeComando(repr(termo) + " casa com mais de uma: " + RelatorioRepasse.nomes(candidatas) + ".");
        }
        return candidatas.getFirst();
    }

    /** As cinco checagens; instituicao null = todas. Criticos primeiro, por tipo (ordem estavel). */
    List<Achado> auditar(UUID instituicao) {
        List<Achado> achados = new ArrayList<>();
        String filtroPedido = instituicao == null ? "" : " and p.instituicao_id = ?";
        Object[] args = instituicao == null ? new Object[0] : new Object[] {instituicao};
        // orfaos na ordem do Pedido.Meta.ordering (-created_at), + id
        jdbc.query("""
                select p.id, p.status, p.valor_centavos, i.nome
                from orders_pedido p join instituicoes_instituicao i on i.id = p.instituicao_id
                where p.eh_gratuito = false and p.valor_centavos > 0
                  and p.status in ('pago', 'gerando_oracao', 'oracao_gerada', 'enviada', 'aguardando_reenvio', 'erro')
                  and not exists (select 1 from instituicoes_repasse r where r.pedido_id = p.id)"""
                + filtroPedido + " order by p.created_at desc, p.id", rs -> {
                    long v = rs.getLong("valor_centavos");
                    long devido = Math.floorDiv(v * 20, 100);
                    achados.add(new Achado(Tipo.ORFAO, rs.getString("nome"), rs.getString("id"), "pedido "
                            + rs.getString("status") + " de " + v + " centavos sem linha no ledger (repasse devido: "
                            + devido + " centavos)", devido));
                }, args);
        // problemas do ledger: o Django nao ordena (ordem fisica); aqui created_at, id
        jdbc.query("""
                select r.pedido_id, r.valor_base_centavos, r.percentual, r.valor_centavos, i.nome, p.status,
                       p.valor_centavos as valor_pedido, p.provider_payment_id
                from instituicoes_repasse r join orders_pedido p on p.id = r.pedido_id
                  join instituicoes_instituicao i on i.id = r.instituicao_id"""
                + (instituicao == null ? "" : " where r.instituicao_id = ?") + " order by r.created_at, r.id", rs -> {
                    String nome = rs.getString("nome");
                    String pedido = rs.getString("pedido_id");
                    long base = rs.getLong("valor_base_centavos");
                    long percentual = rs.getLong("percentual");
                    long valor = rs.getLong("valor_centavos");
                    long esperado = Math.floorDiv(base * percentual, 100);
                    if (valor != esperado) {
                        achados.add(new Achado(Tipo.CALCULO_ERRADO, nome, pedido, "ledger diz " + valor + " centavos; "
                                + percentual + "% de " + base + " dá " + esperado, Math.abs(valor - esperado)));
                    }
                    String status = rs.getString("status");
                    if (!POS_PAGAMENTO.contains(status)) {
                        achados.add(new Achado(Tipo.PEDIDO_NAO_PAGO, nome, pedido,
                                "repasse registrado mas pedido está " + status, valor));
                    }
                    long valorPedido = rs.getLong("valor_pedido");
                    if (valorPedido != base) {
                        achados.add(new Achado(Tipo.DIVERGENCIA_DE_BASE, nome, pedido, "ledger registrou base " + base
                                + "; pedido hoje vale " + valorPedido + " centavos", 0));
                    }
                    String mp = rs.getString("provider_payment_id");
                    if (mp == null || mp.isEmpty()) {
                        achados.add(new Achado(Tipo.SEM_ID_DE_PAGAMENTO, nome, pedido,
                                "sem provider_payment_id — não concilia com o extrato do MP", 0));
                    }
                }, args);
        achados.sort(Comparator.comparing((Achado a) -> !CRITICO.equals(a.gravidade()))
                .thenComparing(a -> a.tipo().valor));
        return achados;
    }
}
