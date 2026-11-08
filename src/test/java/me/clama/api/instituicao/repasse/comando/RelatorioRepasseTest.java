package me.clama.api.instituicao.repasse.comando;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import me.clama.api.BancoDeTeste;
import me.clama.api.RelogioDeTeste;
import me.clama.api.comum.cripto.Fernet;
import me.clama.api.instituicao.repasse.comando.LedgerDeTeste.Saida;
import me.clama.api.instituicao.repasse.comando.RelatorioRepasse.Alvo;
import me.clama.api.instituicao.repasse.comando.RelatorioRepasse.Identificacao;
import me.clama.api.instituicao.repasse.comando.RelatorioRepasse.Linha;
import me.clama.api.instituicao.repasse.comando.RelatorioRepasse.Relatorio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * clama/instituicoes/tests/test_relatorio_repasse.py e TestRelatorioSomentePendentes (test_auditoria_repasse.py),
 * portados: o valor vem do snapshot do ledger, o periodo usa o created_at do repasse com bordas inclusivas em Sao
 * Paulo, e a identificacao padrao e abreviada (nome completo so sob flag). Os de servico chamam gerar(); os de comando
 * rodam o comando como o jar.
 */
@SpringBootTest
@Import({BancoDeTeste.class, RelogioDeTeste.class})
@ActiveProfiles("test")
class RelatorioRepasseTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired Fernet fernet;
    @Autowired ApplicationContext ctx;
    @Autowired RelatorioRepasse relatorio;

    @TempDir Path dir;
    LedgerDeTeste db;

    @BeforeEach
    void limpar() {
        db = new LedgerDeTeste(jdbc, fernet, ctx);
        db.limpar();
    }

    private Relatorio gerar(UUID inst) {
        return gerar(inst, null, null, Identificacao.ABREVIADO, false);
    }

    private Relatorio gerar(UUID inst, LocalDate desde, LocalDate ate, Identificacao modo, boolean auditoria) {
        return relatorio.gerar(new Alvo(inst, "x"), desde, ate, modo, auditoria, false);
    }

    private Saida rodar(String... args) {
        String[] linha = Stream.concat(Stream.of("relatorio_repasse"), Arrays.stream(args)).toArray(String[]::new);
        return db.rodar(dir, linha);
    }

    private String texto(Path csv) throws Exception {
        String t = Files.readString(csv, UTF_8);
        assertThat(t).startsWith("﻿");
        return t.substring(1);
    }

    /** csv.reader(delimiter=";") simples: as celulas dos testes nao tem ; nem aspas. */
    private List<List<String>> celulas(Path csv) throws Exception {
        List<List<String>> linhas = new ArrayList<>();
        for (String l : texto(csv).split("\r\n")) {
            linhas.add(l.isEmpty() ? List.of() : List.of(l.split(";", -1)));
        }
        return linhas;
    }

    /** _linhas_do_csv: so as linhas de doacao. */
    private List<List<String>> doacoes(Path csv) throws Exception {
        return celulas(csv).stream().filter(l -> l.size() == RelatorioRepasse.CABECALHO.size()
                && !l.getFirst().equals("Doador") && !l.getFirst().startsWith("TOTAL")).toList();
    }

    // --- TestAbreviarNome (+ as bordas do mapa §1.4 / §11) ---

    @ParameterizedTest
    @CsvSource(delimiter = '|', ignoreLeadingAndTrailingWhitespace = false, value = {
        "Maria Silva Santos|Maria S.", "Maria da Silva|Maria S.", "Maria|Maria", "''|(sem nome)", "'   '|(sem nome)",
        "ana carolina souza|ana S.", "'  Carla  Dias  '|Carla D.", "Lia Weiß ßauer|Lia SS.", "Bia 😀x|Bia 😀.",
        "Ana　Érica|Ana É."})
    void abreviaPeloUltimoSobrenome(String entrada, String esperado) {
        assertThat(RelatorioRepasse.abreviarNome(entrada)).isEqualTo(esperado);
    }

    // --- TestValores ---

    @Test
    void somaOSnapshotDoLedgerENaoOPedido() {
        UUID inst = db.instituicao();
        UUID pedido = db.pedido(p -> p.valor = 5000);
        db.repasse(inst, r -> {
            r.pedido = pedido;
            r.base = 5000;
            r.valor = 1000;
        });
        db.marcarComoGratuito(pedido);

        Relatorio r = gerar(inst);

        assertThat(r.totalDoado()).isEqualTo(5000);
        assertThat(r.totalRepasse()).isEqualTo(1000);
    }

    @Test
    void totalizaMultiplasDoacoes() {
        UUID inst = db.instituicao();
        for (int base : new int[] {5000, 3000, 10000}) {
            db.repasse(inst, r -> {
                r.base = base;
                r.valor = base * 20 / 100;
            });
        }

        Relatorio r = gerar(inst);

        assertThat(r.quantidade()).isEqualTo(3);
        assertThat(r.totalDoado()).isEqualTo(18000);
        assertThat(r.totalRepasse()).isEqualTo(3600);
    }

    @Test
    void ignoraRepasseDeOutraInstituicao() {
        UUID alvo = db.instituicao();
        UUID outra = db.instituicao();
        db.repasse(alvo, r -> r.valor = 1000);
        db.repasse(outra, r -> r.valor = 9999);

        Relatorio r = gerar(alvo);

        assertThat(r.quantidade()).isEqualTo(1);
        assertThat(r.totalRepasse()).isEqualTo(1000);
    }

    @Test
    void periodoSemRepasseZeraSemEstourar() {
        Relatorio r = gerar(db.instituicao());

        assertThat(r.quantidade()).isZero();
        assertThat(r.totalDoado()).isZero();
        assertThat(r.totalRepasse()).isZero();
    }

    // --- TestPeriodo ---

    @Test
    void bordasSaoInclusivas() {
        UUID inst = db.instituicao();
        db.repasse(inst, r -> { r.criado = LedgerDeTeste.em(LocalDateTime.of(2026, 8, 1, 0, 0)); r.valor = 100; });
        db.repasse(inst, r -> { r.criado = LedgerDeTeste.em(LocalDateTime.of(2026, 8, 31, 23, 59)); r.valor = 200; });
        db.repasse(inst, r -> { r.criado = LedgerDeTeste.em(LocalDateTime.of(2026, 7, 31, 23, 59)); r.valor = 999; });
        db.repasse(inst, r -> { r.criado = LedgerDeTeste.em(LocalDateTime.of(2026, 9, 1, 0, 1)); r.valor = 888; });
        // as bordas de microssegundo (mapa §2.4)
        db.repasse(inst, r -> { r.criado = LedgerDeTeste.em(LocalDateTime.of(2026, 8, 31, 23, 59, 59, 999_999_000));
            r.valor = 1; });
        db.repasse(inst, r -> { r.criado = LedgerDeTeste.em(LocalDateTime.of(2026, 7, 31, 23, 59, 59, 999_999_000));
            r.valor = 7777; });

        Relatorio r = gerar(inst, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), Identificacao.ABREVIADO, false);

        assertThat(r.quantidade()).isEqualTo(3);
        assertThat(r.totalRepasse()).isEqualTo(301);
    }

    @Test
    void ordenaPorDataDaConfirmacao() {
        UUID inst = db.instituicao();
        db.repasse(inst, r -> { r.criado = LedgerDeTeste.em(LocalDate.of(2026, 8, 20)); r.valor = 300; });
        db.repasse(inst, r -> { r.criado = LedgerDeTeste.em(LocalDate.of(2026, 8, 5)); r.valor = 100; });
        db.repasse(inst, r -> { r.criado = LedgerDeTeste.em(LocalDate.of(2026, 8, 12)); r.valor = 200; });

        assertThat(gerar(inst).linhas()).extracting(Linha::valorRepasse).containsExactly(100, 200, 300);
    }

    @Test
    void semPeriodoPegaTudo() {
        UUID inst = db.instituicao();
        db.repasse(inst, r -> r.criado = LedgerDeTeste.em(LocalDate.of(2025, 1, 1)));
        db.repasse(inst, r -> r.criado = LedgerDeTeste.em(LocalDate.of(2026, 8, 15)));

        assertThat(gerar(inst).quantidade()).isEqualTo(2);
    }

    // --- TestIdentificacao ---

    private UUID comMaria(UUID inst) {
        UUID pedido = db.pedido(p -> p.nome = "Maria Silva Santos");
        db.repasse(inst, r -> r.pedido = pedido);
        return pedido;
    }

    @Test
    void abreviadoEOPadrao() {
        UUID inst = db.instituicao();
        comMaria(inst);

        assertThat(gerar(inst).linhas()).singleElement().extracting(Linha::doador).isEqualTo("Maria S.");
    }

    @Test
    void completoExpoeONomeInteiro() {
        UUID inst = db.instituicao();
        comMaria(inst);

        assertThat(gerar(inst, null, null, Identificacao.COMPLETO, false).linhas()).singleElement()
                .extracting(Linha::doador).isEqualTo("Maria Silva Santos");
    }

    @Test
    void anonimoNaoCarregaNenhumPedacoDoNome() {
        UUID inst = db.instituicao();
        UUID pedido = comMaria(inst);

        Linha l = gerar(inst, null, null, Identificacao.ANONIMO, false).linhas().getFirst();

        assertThat(l.doador()).doesNotContain("Maria").doesNotContain("Silva").isEqualTo(l.referencia());
        String hex = pedido.toString().replace("-", "");
        assertThat(l.referencia()).isEqualTo("#" + hex.substring(0, 4) + "…" + hex.substring(28));
    }

    @Test
    void nenhumModoVazaEmailTelefoneOuOPedidoDeOracao() {
        UUID inst = db.instituicao();
        UUID pedido = db.pedido(p -> {
            p.nome = "Maria Silva";
            p.email = "maria@exemplo.com";
            p.telefone = "+5511999998888";
            p.oracao = "Reze pela saúde da minha mãe";
        });
        db.repasse(inst, r -> r.pedido = pedido);

        for (Identificacao modo : Identificacao.values()) {
            String texto = gerar(inst, null, null, modo, false).linhas().getFirst().toString();
            assertThat(texto).as("%s", modo).doesNotContain("maria@exemplo.com").doesNotContain("999998888")
                    .doesNotContain("saúde da minha mãe");
        }
    }

    // --- TestComando ---

    private UUID casaDeApoio() {
        return db.instituicao("Casa de Apoio");
    }

    private void repasseEm(UUID inst, LocalDate dia, String nome, int base, int valor) {
        UUID pedido = db.pedido(p -> p.nome = nome);
        db.repasse(inst, r -> {
            r.pedido = pedido;
            r.criado = LedgerDeTeste.em(dia);
            r.base = base;
            r.valor = valor;
        });
    }

    @Test
    void geraCsvComTotais() throws Exception {
        UUID inst = casaDeApoio();
        repasseEm(inst, LocalDate.of(2026, 8, 12), "Maria Silva Santos", 5000, 1000);
        repasseEm(inst, LocalDate.of(2026, 8, 14), "João Pereira Lima", 3000, 600);
        Path saida = dir.resolve("relatorio.csv");

        Saida s = rodar("--instituicao=Casa de Apoio", "--mes=2026-08", "--saida=" + saida);

        assertThat(s.exit()).isZero();
        List<List<String>> linhas = celulas(saida);
        List<String> conteudo = linhas.stream().flatMap(List::stream).toList();
        assertThat(conteudo).contains("Maria S.", "João L.").doesNotContain("Maria Silva Santos");
        List<String> total = linhas.stream().filter(l -> !l.isEmpty() && l.getFirst().startsWith("TOTAL")).findFirst()
                .orElseThrow();
        assertThat(total.get(3)).isEqualTo("80,00");
        assertThat(total.get(5)).isEqualTo("16,00");
        assertThat(s.out()).contains("R$ 16,00");
    }

    @Test
    void mesRecortaOFechamento() throws Exception {
        UUID inst = casaDeApoio();
        repasseEm(inst, LocalDate.of(2026, 8, 15), "A B", 5000, 1000);
        repasseEm(inst, LocalDate.of(2026, 9, 2), "A B", 25000, 5000);
        Path saida = dir.resolve("agosto.csv");

        rodar("--instituicao=Casa de Apoio", "--mes=2026-08", "--saida=" + saida);

        assertThat(doacoes(saida)).extracting(l -> l.get(2)).containsExactly("15/08/2026");
        assertThat(doacoes(saida)).extracting(l -> l.get(5)).containsExactly("10,00");
    }

    @Test
    void periodoVazioGravaRelatorioZerado() throws Exception {
        casaDeApoio();
        Path saida = dir.resolve("vazio.csv");

        Saida s = rodar("--instituicao=Casa de Apoio", "--mes=2026-08", "--saida=" + saida);

        assertThat(texto(saida)).contains("TOTAL (0 ").contains("0,00");
        assertThat(s.out()).contains("Nenhum repasse");
    }

    @Test
    void resolveInstituicaoPorUuid() {
        UUID inst = casaDeApoio();
        repasseEm(inst, LocalDate.of(2026, 8, 10), "A B", 5000, 1000);
        Path saida = dir.resolve("por-uuid.csv");

        assertThat(rodar("--instituicao=" + inst, "--saida=" + saida).exit()).isZero();
        assertThat(saida).exists();
        // as outras formas que o UUID() do Python aceita
        String hex = inst.toString().replace("-", "");
        for (String forma : List.of(hex.toUpperCase(), "{" + inst + "}", "urn:uuid:" + inst)) {
            assertThat(rodar("--instituicao=" + forma, "--apenas-resumo").exit()).as(forma).isZero();
        }
    }

    @Test
    void nomeAmbiguoFalhaEmVezDeEscolher() {
        db.instituicao("Casa de Apoio Norte");
        db.instituicao("Casa de Apoio Sul");

        Saida s = rodar("--instituicao=Casa de Apoio");

        assertThat(s.exit()).isEqualTo(1);
        assertThat(s.err()).startsWith("CommandError: ").contains("mais de uma instituição");
    }

    @Test
    void instituicaoInexistenteListaAsCadastradas() {
        casaDeApoio();

        Saida s = rodar("--instituicao=Lar São José");

        assertThat(s.exit()).isEqualTo(1);
        assertThat(s.err()).isEqualTo(
                "CommandError: Nenhuma instituição casa com 'Lar São José'. Cadastradas: Casa de Apoio\n");
    }

    @Test
    void mesComDesdeOuAteERecusado() {
        casaDeApoio();

        assertThat(rodar("--instituicao=Casa de Apoio", "--mes=2026-08", "--desde=2026-08-01").err())
                .contains("não os dois");
    }

    @Test
    void desdePosteriorAAteERecusado() {
        casaDeApoio();

        assertThat(rodar("--instituicao=Casa de Apoio", "--desde=2026-08-31", "--ate=2026-08-01").err())
                .contains("posterior");
    }

    @Test
    void dataEmFormatoInvalidoERecusada() {
        casaDeApoio();

        Saida s = rodar("--instituicao=Casa de Apoio", "--desde=31-08-2026");

        assertThat(s.exit()).isEqualTo(1);
        assertThat(s.err()).contains("inválida");
        // strptime: 1 digito passa, data inexistente e sobra no fim nao (mapa §1.2)
        assertThat(RelatorioRepasse.parseData("2026-8-1", "--desde")).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(RelatorioRepasse.parseData("1/8/2026", "--desde")).isEqualTo(LocalDate.of(2026, 8, 1));
        for (String ruim : List.of("2026-02-30", "2026-08-01 ", "0000-01-01", "2026-010-01", "26-08-01")) {
            assertThat(rodar("--instituicao=Casa de Apoio", "--ate=" + ruim).err()).as(ruim).contains("inválida");
        }
    }

    @Test
    void dezembroViraJaneiroSemEstourar() throws Exception {
        UUID inst = casaDeApoio();
        UUID p1 = db.pedido(p -> { });
        db.repasse(inst, r -> { r.pedido = p1; r.criado = LedgerDeTeste.em(LocalDateTime.of(2026, 12, 31, 23, 30));
            r.base = 5000; r.valor = 1000; });
        UUID p2 = db.pedido(p -> { });
        db.repasse(inst, r -> { r.pedido = p2; r.criado = LedgerDeTeste.em(LocalDateTime.of(2027, 1, 1, 0, 30));
            r.base = 25000; r.valor = 5000; });
        Path saida = dir.resolve("dezembro.csv");

        rodar("--instituicao=Casa de Apoio", "--mes=2026-12", "--saida=" + saida);

        assertThat(doacoes(saida)).extracting(l -> l.get(2)).containsExactly("31/12/2026");
        assertThat(doacoes(saida)).extracting(l -> l.get(5)).containsExactly("10,00");
    }

    @Test
    void identificacaoCompletaAvisaSobreAPolitica() throws Exception {
        UUID inst = casaDeApoio();
        repasseEm(inst, LocalDate.of(2026, 8, 10), "Maria Silva Santos", 5000, 1000);
        Path saida = dir.resolve("completo.csv");

        Saida s = rodar("--instituicao=Casa de Apoio", "--identificacao=completo", "--saida=" + saida);

        assertThat(texto(saida)).contains("Maria Silva Santos");
        assertThat(s.out()).contains("Política de Privacidade");
    }

    @Test
    void umaDoacaoSaiNoSingularEPeriodoPorExtenso() throws Exception {
        UUID inst = casaDeApoio();
        repasseEm(inst, LocalDate.of(2026, 8, 10), "A B", 5000, 1000);
        Path saida = dir.resolve("singular.csv");

        rodar("--instituicao=Casa de Apoio", "--mes=2026-08", "--saida=" + saida);

        assertThat(texto(saida)).contains("TOTAL (1 doação)").doesNotContain("doações")
                .contains("Período: 01/08/2026 a 31/08/2026");
    }

    @Test
    void semPeriodoOCsvDizDesdeOInicio() throws Exception {
        UUID inst = casaDeApoio();
        repasseEm(inst, LocalDate.of(2026, 8, 10), "A B", 5000, 1000);
        Path saida = dir.resolve("tudo.csv");

        rodar("--instituicao=Casa de Apoio", "--saida=" + saida);

        assertThat(texto(saida)).contains("Período: desde o início");
    }

    @Test
    void apenasResumoNaoGravaArquivo() {
        UUID inst = casaDeApoio();
        repasseEm(inst, LocalDate.of(2026, 8, 10), "A B", 5000, 1000);
        Path saida = dir.resolve("nao-deve-existir.csv");

        Saida s = rodar("--instituicao=Casa de Apoio", "--apenas-resumo", "--saida=" + saida);

        assertThat(saida).doesNotExist();
        assertThat(s.out()).contains("R$ 10,00");
    }

    // --- TestAuditoria ---

    private UUID pedidoPago(java.util.function.Consumer<LedgerDeTeste.Pedido> ajuste) {
        return db.pedido(p -> {
            p.nome = "Maria Silva Santos";
            p.email = "maria@exemplo.com";
            p.valor = 5000;
            p.status = "enviada";
            p.mp = "MP-123456789";
            ajuste.accept(p);
        });
    }

    private void comPedido(UUID inst, UUID pedido) {
        db.repasse(inst, r -> r.pedido = pedido);
    }

    @Test
    void trazEmailEIdDoPagamento() {
        UUID inst = db.instituicao();
        comPedido(inst, pedidoPago(p -> { }));

        Linha l = gerar(inst, null, null, Identificacao.ABREVIADO, true).linhas().getFirst();

        assertThat(l.email()).isEqualTo("maria@exemplo.com");
        assertThat(l.providerPaymentId()).isEqualTo("MP-123456789");
        assertThat(l.statusPedido()).isEqualTo("enviada");
        assertThat(l.doador()).isEqualTo("Maria Silva Santos");
    }

    @Test
    void semAuditoriaOsCamposFicamNulos() {
        UUID inst = db.instituicao();
        comPedido(inst, pedidoPago(p -> { }));

        Linha l = gerar(inst).linhas().getFirst();

        assertThat(l.email()).isNull();
        assertThat(l.providerPaymentId()).isNull();
        assertThat(l.statusPedido()).isNull();
    }

    @Test
    void divergenciaQuandoPedidoFoiZeradoDepois() {
        UUID inst = db.instituicao();
        UUID pedido = pedidoPago(p -> p.status = "pago");
        db.repasse(inst, r -> { r.pedido = pedido; r.base = 5000; r.valor = 1000; });
        db.marcarComoGratuito(pedido);

        Linha l = gerar(inst, null, null, Identificacao.ABREVIADO, true).linhas().getFirst();

        assertThat(l.divergencia()).isEqualTo("pedido hoje vale 0 centavos, ledger registrou 5000");
    }

    @Test
    void semDivergenciaQuandoBate() {
        UUID inst = db.instituicao();
        UUID pedido = pedidoPago(p -> p.valor = 5000);
        db.repasse(inst, r -> { r.pedido = pedido; r.base = 5000; });

        assertThat(gerar(inst, null, null, Identificacao.ABREVIADO, true).linhas().getFirst().divergencia()).isEmpty();
    }

    @Test
    void csvDeAuditoriaTemAsColunasDeConciliacao() throws Exception {
        UUID inst = casaDeApoio();
        UUID pedido = pedidoPago(p -> { });
        db.repasse(inst, r -> { r.pedido = pedido; r.criado = LedgerDeTeste.em(LocalDate.of(2026, 8, 12)); });
        Path saida = dir.resolve("auditoria.csv");

        rodar("--instituicao=Casa de Apoio", "--auditoria", "--saida=" + saida);

        assertThat(texto(saida)).contains("maria@exemplo.com", "MP-123456789", "Maria Silva Santos", "USO INTERNO")
                .contains(RelatorioRepasse.CABECALHO_AUDITORIA);
    }

    @Test
    void csvDaInstituicaoNuncaLevaEmail() throws Exception {
        UUID inst = casaDeApoio();
        UUID pedido = pedidoPago(p -> { });
        db.repasse(inst, r -> { r.pedido = pedido; r.criado = LedgerDeTeste.em(LocalDate.of(2026, 8, 12)); });
        Path saida = dir.resolve("instituicao.csv");

        rodar("--instituicao=Casa de Apoio", "--saida=" + saida);

        assertThat(texto(saida)).doesNotContain("maria@exemplo.com").doesNotContain("MP-123456789")
                .doesNotContain("Maria Silva Santos").contains("Maria S.");
    }

    @Test
    void avisaQuandoFaltaIdDePagamento() {
        UUID inst = casaDeApoio();
        UUID pedido = pedidoPago(p -> p.mp = null);
        db.repasse(inst, r -> r.pedido = pedido);

        Saida s = rodar("--instituicao=Casa de Apoio", "--auditoria", "--apenas-resumo");

        assertThat(s.out()).contains("sem id de pagamento no gateway");
    }

    @Test
    void avisaQueOArquivoEInterno() {
        UUID inst = casaDeApoio();
        comPedido(inst, pedidoPago(p -> { }));

        Saida s = rodar("--instituicao=Casa de Apoio", "--auditoria", "--saida=" + dir.resolve("auditoria.csv"));

        assertThat(s.out()).contains("Não entregar à instituição");
    }

    @Test
    void nomePadraoDoArquivoMarcaQueEInterno() throws Exception {
        UUID inst = casaDeApoio();
        comPedido(inst, pedidoPago(p -> { }));

        Saida s = rodar("--instituicao=Casa de Apoio", "--auditoria");

        try (Stream<Path> arquivos = Files.list(dir)) {
            assertThat(arquivos.map(p -> p.getFileName().toString()))
                    .containsExactly("auditoria-INTERNA-repasse-casa-de-apoio-completo.csv");
        }
        assertThat(s.out()).endsWith("\nCSV gravado em: " + dir.toRealPath()
                .resolve("auditoria-INTERNA-repasse-casa-de-apoio-completo.csv") + "\n");
    }

    // --- fuso ---

    @Test
    void repasseDeHojeEntraNoFechamentoDoMesCorrente() {
        UUID inst = db.instituicao();
        ZonedDateTime agora = ZonedDateTime.now(RelatorioRepasse.SAO_PAULO);
        db.repasse(inst, r -> r.valor = 1000);

        Relatorio r = gerar(inst, agora.toLocalDate(), agora.toLocalDate(), Identificacao.ABREVIADO, false);

        assertThat(r.quantidade()).isEqualTo(1);
        assertThat(r.linhas().getFirst().data()).isEqualTo(agora.toLocalDate());
    }

    // --- TestRelatorioSomentePendentes ---

    @Test
    void somentePendentesRecortaOQueFaltaPagar() throws Exception {
        UUID inst = casaDeApoio();
        UUID a = db.pedido(p -> p.nome = "Maria Silva");
        UUID b = db.pedido(p -> p.nome = "João Lima");
        UUID pago = db.repasse(inst, r -> { r.pedido = a; r.base = 5000; r.valor = 1000; });
        db.repasse(inst, r -> { r.pedido = b; r.base = 3000; r.valor = 600; });
        db.transferir(pago, "E123");
        Path saida = dir.resolve("pendentes.csv");

        rodar("--instituicao=Casa de Apoio", "--somente-pendentes", "--saida=" + saida);

        assertThat(texto(saida)).contains("João L.").doesNotContain("Maria S.").contains("TOTAL (1 doação)");
    }

    @Test
    void semAFlagTrazTudo() throws Exception {
        UUID inst = casaDeApoio();
        UUID pago = db.repasse(inst, r -> r.valor = 1000);
        db.repasse(inst, r -> r.valor = 600);
        db.transferir(pago, "E123");
        Path saida = dir.resolve("tudo.csv");

        rodar("--instituicao=Casa de Apoio", "--saida=" + saida);

        assertThat(texto(saida)).contains("TOTAL (2 doações)");
    }

    @Test
    void auditoriaMostraStatusEComprovante() throws Exception {
        UUID inst = casaDeApoio();
        UUID pedido = db.pedido(p -> { p.instituicao = inst; p.mp = "MP-1"; });
        UUID repasse = db.repasse(inst, r -> { r.pedido = pedido; r.base = 5000; r.valor = 1000; });
        db.transferir(repasse, "E123-PIX");
        Path saida = dir.resolve("auditoria.csv");

        rodar("--instituicao=Casa de Apoio", "--auditoria", "--saida=" + saida);

        assertThat(texto(saida)).contains("transferido").contains("E123-PIX");
    }

    // --- Java: bordas do CLI ---

    @Test
    void semInstituicaoEUsoComExit2() {
        Saida s = rodar("--mes=2026-08");

        assertThat(s.exit()).isEqualTo(2);
        assertThat(s.err()).endsWith("relatorio_repasse: error: the following arguments are required: --instituicao\n");
    }
}
