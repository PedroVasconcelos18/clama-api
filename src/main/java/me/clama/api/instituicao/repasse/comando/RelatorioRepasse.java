package me.clama.api.instituicao.repasse.comando;

import static me.clama.api.comum.texto.TextoPython.repr;

import java.io.PrintStream;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import me.clama.api.comum.Dinheiro;
import me.clama.api.comum.comando.Chamada;
import me.clama.api.comum.comando.Comando;
import me.clama.api.comum.comando.Comandos.Opcao;
import me.clama.api.comum.comando.ErroDeComando;
import me.clama.api.comum.cripto.CampoCriptografado;
import me.clama.api.comum.texto.TextoPython;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * relatorio_repasse (instituicoes/management/commands/relatorio_repasse.py + services/relatorio.py, versao do working
 * tree; mapa §1): prestacao de contas de uma instituicao, lida do ledger (snapshot), com CSV e stdout iguais aos do
 * Django byte a byte. Somente leitura. --auditoria imprime e grava nome completo e e-mail (arquivo interno; paridade).
 */
@Component
public class RelatorioRepasse implements Comando {
    public static final List<Opcao> OPCOES = List.of(Opcao.obrigatoria("--instituicao"), Opcao.valor("--mes"),
            Opcao.valor("--desde"), Opcao.valor("--ate"),
            Opcao.valor("--identificacao", "abreviado", "completo", "anonimo"), Opcao.valor("--saida"),
            Opcao.flag("--auditoria"), Opcao.flag("--somente-pendentes"), Opcao.flag("--apenas-resumo"));

    static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    static final DateTimeFormatter BR = DateTimeFormatter.ofPattern("dd/MM/uuuu");
    static final String SEM_NOME = "(sem nome)";
    static final List<String> CABECALHO = List.of("Doador", "Referência do pedido", "Data da doação",
            "Valor doado (R$)", "Percentual de repasse", "Valor a receber (R$)");
    static final List<String> CABECALHO_AUDITORIA = List.of("Doador (nome completo)", "E-mail", "ID do pedido",
            "ID do pagamento no gateway", "Status do pedido", "Data do pedido", "Data da confirmação",
            "Valor no ledger (R$)", "Valor atual do pedido (R$)", "Percentual", "Repasse (R$)", "Status do repasse",
            "Transferido em", "Comprovante", "Divergência");

    // os regex do _strptime do Python 3.14 (\d e Unicode, como no re do Python); match inteiro = sem "unconverted data"
    private static final String ANO = "(\\d\\d\\d\\d)";
    private static final String MES = "(1[0-2]|0[1-9]|[1-9])";
    private static final String DIA = "(3[0-1]|[1-2]\\d|0[1-9]|[1-9]| [1-9])";
    private static final Pattern ISO = Pattern.compile(ANO + "-" + MES + "-" + DIA, Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern BRASIL = Pattern.compile(DIA + "/" + MES + "/" + ANO, Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern ANO_MES = Pattern.compile(ANO + "-" + MES, Pattern.UNICODE_CHARACTER_CLASS);

    private static final String INSTITUICOES = "select id, nome from instituicoes_instituicao";
    private static final String EM_ORDEM = " order by ordem, nome, id"; // Meta.ordering (+ id, invisivel)
    private static final RowMapper<Alvo> ALVO = (rs, n) -> new Alvo(rs.getObject("id", UUID.class), rs.getString("nome"));
    private static final CampoCriptografado CIFRA = new CampoCriptografado();

    private final JdbcTemplate jdbc;

    RelatorioRepasse(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    enum Identificacao { ABREVIADO, COMPLETO, ANONIMO }

    record Alvo(UUID id, String nome) {}

    record Periodo(LocalDate desde, LocalDate ate) {}

    /** Uma doacao. Os campos de auditoria ficam null sem --auditoria (um bug de coluna nao vaza e-mail). */
    record Linha(UUID pedidoId, String referencia, String doador, LocalDate data, int valorDoacao, int percentual,
            int valorRepasse, String email, String statusRepasse, LocalDate transferidoEm, String comprovante,
            String statusPedido, String providerPaymentId, LocalDate pedidoCriadoEm, Integer valorPedidoAtual) {
        String divergencia() {
            if (valorPedidoAtual == null || valorPedidoAtual == valorDoacao) {
                return "";
            }
            return "pedido hoje vale " + valorPedidoAtual + " centavos, ledger registrou " + valorDoacao;
        }
    }

    record Relatorio(Alvo instituicao, LocalDate desde, LocalDate ate, List<Linha> linhas) {
        long totalDoado() {
            return linhas.stream().mapToLong(Linha::valorDoacao).sum();
        }

        long totalRepasse() {
            return linhas.stream().mapToLong(Linha::valorRepasse).sum();
        }

        int quantidade() {
            return linhas.size();
        }
    }

    @Override
    public int executar(Chamada c) throws Exception {
        Alvo instituicao = resolverInstituicao(c.valor("--instituicao"));
        Periodo periodo = resolverPeriodo(c);
        boolean auditoria = c.tem("--auditoria");
        Identificacao identificacao = Identificacao.valueOf(
                c.opcoes().getOrDefault("--identificacao", "abreviado").toUpperCase(java.util.Locale.ROOT));

        Relatorio r = gerar(instituicao, periodo.desde(), periodo.ate(), identificacao, auditoria,
                c.tem("--somente-pendentes"));
        imprimirResumo(c.out(), r, identificacao, auditoria);
        // mes sem doacao tambem vai para a instituicao: a prestacao de contas zerada prova que nao faltou repasse
        if (r.quantidade() == 0) {
            c.out().println("Nenhum repasse no período.");
        }
        if (c.tem("--apenas-resumo")) {
            return 0;
        }
        String saida = preenchida(c, "--saida");
        Path destino = c.caminho(saida != null ? saida : nomePadrao(r, auditoria));
        (auditoria ? csvAuditoria(r) : csvInstituicao(r)).gravar(destino);
        c.out().println();
        c.out().println("CSV gravado em: " + destino.toRealPath()); // Path.resolve() do Python: symlink resolvido
        return 0;
    }

    /** _resolver_instituicao (mapa §0.4): UUID, senao nome exato (sem caixa), senao parte do nome; um e so um. */
    Alvo resolverInstituicao(String termo) {
        UUID id = uuidDoPython(termo);
        if (id != null) {
            return jdbc.query(INSTITUICOES + " where id = ?", ALVO, id).stream().findFirst()
                    .orElseThrow(() -> new ErroDeComando("Nenhuma instituição com o id " + termo + "."));
        }
        List<Alvo> candidatas = jdbc.query(INSTITUICOES + " where upper(nome::text) = upper(?)" + EM_ORDEM, ALVO, termo);
        if (candidatas.isEmpty()) {
            candidatas = contendo(jdbc, termo);
        }
        if (candidatas.isEmpty()) {
            String todas = String.join(", ", jdbc.queryForList("select nome from instituicoes_instituicao" + EM_ORDEM,
                    String.class));
            throw new ErroDeComando("Nenhuma instituição casa com " + repr(termo) + ". Cadastradas: "
                    + (todas.isEmpty() ? "(nenhuma)" : todas));
        }
        if (candidatas.size() > 1) {
            throw new ErroDeComando(repr(termo) + " casa com mais de uma instituição: " + nomes(candidatas)
                    + ". Use o nome exato ou o UUID.");
        }
        return candidatas.getFirst();
    }

    /** nome__icontains: UPPER(nome::text) LIKE UPPER('%termo%'), com % _ \ escapados (o SQL do Django). */
    static List<Alvo> contendo(JdbcTemplate jdbc, String termo) {
        String padrao = "%" + termo.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.query(INSTITUICOES + " where upper(nome::text) like upper(?)" + EM_ORDEM, ALVO, padrao);
    }

    static String nomes(List<Alvo> alvos) {
        return alvos.stream().map(a -> repr(a.nome())).collect(Collectors.joining(", "));
    }

    /**
     * UUID(termo) do Python: tira "urn:", "uuid:", chaves das pontas e hifens; sobram 32 hex. null = nao e UUID.
     * ponytail: o int(hex, 16) do Python ainda aceitaria '_' entre digitos e sinal; nome de instituicao nao tem isso.
     */
    static UUID uuidDoPython(String termo) {
        String h = termo.replace("urn:", "").replace("uuid:", "");
        int i = 0;
        int f = h.length();
        while (i < f && (h.charAt(i) == '{' || h.charAt(i) == '}')) {
            i++;
        }
        while (f > i && (h.charAt(f - 1) == '{' || h.charAt(f - 1) == '}')) {
            f--;
        }
        h = h.substring(i, f).replace("-", "");
        if (h.length() != 32 || !h.chars().allMatch(ch -> Character.digit(ch, 16) >= 0 && ch < 128)) {
            return null;
        }
        return new UUID(Long.parseUnsignedLong(h.substring(0, 16), 16), Long.parseUnsignedLong(h.substring(16), 16));
    }

    /** _resolver_periodo: --mes OU --desde/--ate (vazio = ausente, como o "if options[...]" do Django). */
    Periodo resolverPeriodo(Chamada c) {
        String mes = preenchida(c, "--mes");
        String desdeTexto = preenchida(c, "--desde");
        String ateTexto = preenchida(c, "--ate");
        if (mes != null && (desdeTexto != null || ateTexto != null)) {
            throw new ErroDeComando("Use --mes OU --desde/--ate, não os dois.");
        }
        if (mes != null) {
            Matcher m = ANO_MES.matcher(mes);
            LocalDate primeiro = m.matches() ? data(m.group(1), m.group(2), "1") : null;
            if (primeiro == null) {
                throw new ErroDeComando("--mes inválido: " + repr(mes) + ". Use AAAA-MM.");
            }
            return new Periodo(primeiro, primeiro.plusMonths(1).minusDays(1));
        }
        LocalDate desde = desdeTexto == null ? null : parseData(desdeTexto, "--desde");
        LocalDate ate = ateTexto == null ? null : parseData(ateTexto, "--ate");
        if (desde != null && ate != null && desde.isAfter(ate)) {
            throw new ErroDeComando("--desde (" + desde + ") é posterior a --ate (" + ate + ").");
        }
        return new Periodo(desde, ate);
    }

    static String preenchida(Chamada c, String nome) {
        String v = c.valor(nome);
        return v == null || v.isEmpty() ? null : v;
    }

    /** strptime com "%Y-%m-%d", depois "%d/%m/%Y": mes e dia com 1 digito passam; data inexistente nao. */
    static LocalDate parseData(String valor, String rotulo) {
        Matcher iso = ISO.matcher(valor);
        Matcher br = BRASIL.matcher(valor);
        LocalDate d = iso.matches() ? data(iso.group(1), iso.group(2), iso.group(3))
                : br.matches() ? data(br.group(3), br.group(2), br.group(1)) : null;
        if (d == null) {
            throw new ErroDeComando(rotulo + " inválida: " + repr(valor) + ". Use AAAA-MM-DD ou DD/MM/AAAA.");
        }
        return d;
    }

    private static LocalDate data(String ano, String mes, String dia) {
        try {
            int a = Integer.parseInt(ano); // Character.digit: aceita os digitos Unicode que o \d casou
            return a < 1 ? null : LocalDate.of(a, Integer.parseInt(mes), Integer.parseInt(dia.strip()));
        } catch (DateTimeException e) {
            return null;
        }
    }

    /** Inicio inclusivo e fim exclusivo do periodo em Sao Paulo (= created_at <= ate 23:59:59.999999 local). */
    static OffsetDateTime inicio(LocalDate dia) {
        return dia.atStartOfDay(SAO_PAULO).toOffsetDateTime();
    }

    static OffsetDateTime depoisDoFim(LocalDate dia) {
        return dia.plusDays(1).atStartOfDay(SAO_PAULO).toOffsetDateTime();
    }

    static LocalDate diaLocal(OffsetDateTime instante) {
        return instante == null ? null : instante.atZoneSameInstant(SAO_PAULO).toLocalDate();
    }

    /** gerar_relatorio: o ledger (snapshot), nao o pedido; bordas inclusivas; --auditoria promove a COMPLETO. */
    Relatorio gerar(Alvo instituicao, LocalDate desde, LocalDate ate, Identificacao identificacao, boolean auditoria,
            boolean somentePendentes) {
        Identificacao modo = auditoria ? Identificacao.COMPLETO : identificacao;
        StringBuilder sql = new StringBuilder("""
                select r.pedido_id, r.valor_base_centavos, r.percentual, r.valor_centavos, r.status, r.transferido_em,
                       r.comprovante, r.created_at, p.nome, p.email, p.status as status_pedido, p.provider_payment_id,
                       p.created_at as pedido_criado_em, p.valor_centavos as valor_pedido
                from instituicoes_repasse r join orders_pedido p on p.id = r.pedido_id
                where r.instituicao_id = ?""");
        List<Object> args = new ArrayList<>(List.of(instituicao.id()));
        if (somentePendentes) {
            sql.append(" and r.status = 'registrado'");
        }
        if (desde != null) {
            sql.append(" and r.created_at >= ?");
            args.add(inicio(desde));
        }
        if (ate != null) {
            sql.append(" and r.created_at < ?");
            args.add(depoisDoFim(ate));
        }
        sql.append(" order by r.created_at, r.id"); // + id: desempate invisivel
        List<Linha> linhas = jdbc.query(sql.toString(), (rs, n) -> {
            UUID pedido = rs.getObject("pedido_id", UUID.class);
            String referencia = referencia(pedido);
            // decifra so quando o modo pede o nome
            String doador = switch (modo) {
                case ANONIMO -> referencia;
                case COMPLETO -> Objects.requireNonNullElse(emptyToNull(decifrar(rs.getString("nome"))), SEM_NOME);
                case ABREVIADO -> abreviarNome(decifrar(rs.getString("nome")));
            };
            return new Linha(pedido, referencia, doador, diaLocal(rs.getObject("created_at", OffsetDateTime.class)),
                    rs.getInt("valor_base_centavos"), rs.getInt("percentual"), rs.getInt("valor_centavos"),
                    auditoria ? Objects.requireNonNullElse(decifrar(rs.getString("email")), "") : null,
                    auditoria ? rs.getString("status") : null,
                    auditoria ? diaLocal(rs.getObject("transferido_em", OffsetDateTime.class)) : null,
                    auditoria ? Objects.requireNonNullElse(rs.getString("comprovante"), "") : null,
                    auditoria ? rs.getString("status_pedido") : null,
                    auditoria ? Objects.requireNonNullElse(rs.getString("provider_payment_id"), "") : null,
                    auditoria ? diaLocal(rs.getObject("pedido_criado_em", OffsetDateTime.class)) : null,
                    auditoria ? rs.getInt("valor_pedido") : null);
        }, args.toArray());
        return new Relatorio(instituicao, desde, ate, linhas);
    }

    private static String decifrar(String coluna) {
        return CIFRA.convertToEntityAttribute(coluna);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /** "#a3f2…8b1c": referencia curta do pedido, sem PII. */
    static String referencia(UUID pedido) {
        String hex = pedido.toString().replace("-", "");
        return "#" + hex.substring(0, 4) + "…" + hex.substring(28);
    }

    /**
     * abreviar_nome: "Maria Silva Santos" -> "Maria S."; split() do Python; o 1o code point da ultima parte em
     * maiuscula como o upper() do Python (ß vira SS); sem partes = "(sem nome)".
     */
    static String abreviarNome(String nome) {
        List<String> partes = TextoPython.split(nome);
        if (partes.isEmpty()) {
            return SEM_NOME;
        }
        if (partes.size() == 1) {
            return partes.getFirst();
        }
        String ultima = partes.getLast();
        return partes.getFirst() + " "
                + Character.toString(ultima.codePointAt(0)).toUpperCase(java.util.Locale.ROOT) + ".";
    }

    static String periodoPorExtenso(LocalDate desde, LocalDate ate) {
        if (desde == null && ate == null) {
            return "desde o início";
        }
        return (desde != null ? BR.format(desde) : "o início") + " a " + (ate != null ? BR.format(ate) : "hoje");
    }

    /** _nome_padrao: <prefixo>-<slug>-<periodo>.csv no diretorio de trabalho. */
    static String nomePadrao(Relatorio r, boolean auditoria) {
        String periodo = "completo";
        if (r.desde() != null || r.ate() != null) {
            periodo = (r.desde() != null ? r.desde().toString() : "inicio") + "_a_"
                    + (r.ate() != null ? r.ate().toString() : "hoje");
        }
        return (auditoria ? "auditoria-INTERNA-repasse" : "relatorio-repasse") + "-" + slug(r.instituicao().nome())
                + "-" + periodo + ".csv";
    }

    /** NFKD -> so ASCII -> lower -> split() -> "-".join, ou "instituicao". */
    static String slug(String texto) {
        String ascii = Normalizer.normalize(texto, Normalizer.Form.NFKD).codePoints().filter(c -> c < 128)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        String s = String.join("-", TextoPython.split(ascii.toLowerCase(java.util.Locale.ROOT)));
        return s.isEmpty() ? "instituicao" : s;
    }

    /** f"{s:<n}" do Python: largura em code points. */
    static String esquerda(String s, int largura) {
        return s + " ".repeat(Math.max(0, largura - s.codePointCount(0, s.length())));
    }

    /** f"{s:>n}" do Python. */
    static String direita(String s, int largura) {
        return " ".repeat(Math.max(0, largura - s.codePointCount(0, s.length()))) + s;
    }

    static String reais(long centavos) {
        return Dinheiro.centavosParaReaisStr(centavos);
    }

    /** _centavos_para_planilha: "1234,50", sem "R$" nem milhar. */
    static String planilha(long centavos) {
        return reais(centavos).substring("R$ ".length());
    }

    private static String plural(int n) {
        return n == 1 ? "doação" : "doações";
    }

    private void imprimirResumo(PrintStream out, Relatorio r, Identificacao identificacao, boolean auditoria) {
        out.println();
        out.println("RELATÓRIO DE REPASSE — " + r.instituicao().nome());
        out.println("Período: " + periodoPorExtenso(r.desde(), r.ate()));
        out.println();

        int largura = Math.max("Doador".length(),
                r.linhas().stream().mapToInt(l -> l.doador().codePointCount(0, l.doador().length())).max().orElse(6));
        out.println(esquerda("Doador", largura) + "  " + esquerda("Data", 10) + "  " + direita("Doação", 12) + "  "
                + direita("Repasse", 12));
        for (Linha l : r.linhas()) {
            out.println(esquerda(l.doador(), largura) + "  " + esquerda(BR.format(l.data()), 10) + "  "
                    + direita(reais(l.valorDoacao()), 12) + "  " + direita(reais(l.valorRepasse()), 12));
        }
        out.println("─".repeat(largura + 40));
        out.println(esquerda("TOTAL (" + r.quantidade() + ")", largura) + "  " + esquerda("", 10) + "  "
                + direita(reais(r.totalDoado()), 12) + "  " + direita(reais(r.totalRepasse()), 12));

        List<Linha> divergentes = r.linhas().stream().filter(l -> !l.divergencia().isEmpty()).toList();
        if (!divergentes.isEmpty()) {
            out.println();
            out.println(divergentes.size() + " pedido(s) com valor divergente do ledger:");
            divergentes.forEach(l -> out.println("  " + l.referencia() + ": " + l.divergencia()));
        }
        List<Linha> semPagamento = auditoria
                ? r.linhas().stream().filter(l -> l.providerPaymentId().isEmpty()).toList() : List.of();
        if (!semPagamento.isEmpty()) {
            out.println();
            out.println(semPagamento.size() + " repasse(s) sem id de pagamento no gateway — "
                    + "não dá pra conciliar com o extrato do Mercado Pago:");
            semPagamento.forEach(l -> out.println("  " + l.referencia() + " (" + BR.format(l.data()) + ")"));
        }
        if (auditoria) {
            out.println();
            out.println("ARQUIVO INTERNO: contém nome completo, e-mail e id de pagamento. Não entregar à instituição.");
        } else if (identificacao == Identificacao.COMPLETO) {
            out.println();
            out.println("ATENÇÃO: nome completo dos doadores. A Política de Privacidade vigente não lista instituições "
                    + "parceiras como destinatárias de dados pessoais — atualize a política antes de entregar.");
        }
    }

    private static PlanilhaCsv cabecalho(Relatorio r, String titulo) {
        return new PlanilhaCsv().linha(titulo + " — " + r.instituicao().nome())
                .linha("Período: " + periodoPorExtenso(r.desde(), r.ate()));
    }

    static PlanilhaCsv csvInstituicao(Relatorio r) {
        PlanilhaCsv csv = cabecalho(r, "Relatório de repasse").linha().linha(CABECALHO.toArray(String[]::new));
        for (Linha l : r.linhas()) {
            csv.linha(l.doador(), l.referencia(), BR.format(l.data()), planilha(l.valorDoacao()), l.percentual() + "%",
                    planilha(l.valorRepasse()));
        }
        return csv.linha().linha("TOTAL (" + r.quantidade() + " " + plural(r.quantidade()) + ")", "", "",
                planilha(r.totalDoado()), "", planilha(r.totalRepasse()));
    }

    static PlanilhaCsv csvAuditoria(Relatorio r) {
        PlanilhaCsv csv = cabecalho(r, "AUDITORIA INTERNA de repasse")
                .linha("USO INTERNO — contém dado pessoal. Não entregar à instituição.").linha()
                .linha(CABECALHO_AUDITORIA.toArray(String[]::new));
        for (Linha l : r.linhas()) {
            csv.linha(l.doador(), l.email(), l.pedidoId().toString(),
                    l.providerPaymentId().isEmpty() ? "(sem id no gateway)" : l.providerPaymentId(),
                    Objects.requireNonNullElse(l.statusPedido(), ""), BR.format(l.pedidoCriadoEm()),
                    BR.format(l.data()), planilha(l.valorDoacao()), planilha(l.valorPedidoAtual()), l.percentual() + "%",
                    planilha(l.valorRepasse()), l.statusRepasse(),
                    l.transferidoEm() != null ? BR.format(l.transferidoEm()) : "", l.comprovante(), l.divergencia());
        }
        String[] total = new String[CABECALHO_AUDITORIA.size()];
        java.util.Arrays.fill(total, "");
        total[0] = "TOTAL (" + r.quantidade() + " " + plural(r.quantidade()) + ")";
        total[7] = planilha(r.totalDoado());
        total[10] = planilha(r.totalRepasse());
        return csv.linha().linha(total);
    }
}
