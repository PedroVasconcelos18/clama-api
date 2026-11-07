package me.clama.api.contrato;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import me.clama.api.BancoDeTeste;
import me.clama.api.RelogioDeTeste;
import me.clama.api.comum.ratelimit.RateLimiter;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.token.ServicoDeTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Cenarios gravados por clama-backend/contrato/test_gravar_comandos.py (etapa 8): os management commands que o jar
 * porta, rodados pelo {@link ExecutorDeComando} e comparados pelo {@link ReplayDeContrato#conferirComando}. O modo
 * comando ainda nao existe (Task 2): tudo pendente. Os testes sinteticos provam o harness com um executor falso que
 * devolve o gravado ({@link Eco}).
 */
@SpringBootTest
@Import({BancoDeTeste.class, RelogioDeTeste.class})
@ActiveProfiles("test")
class ContratoComandosTest {
    static final Path CENARIOS = Path.of("src/test/resources/contrato/comandos");
    /** Gravados (os .json da pasta). */
    static final int GRAVADOS = 59;
    /** Grupos ligados, por prefixo do arquivo. Cada task move o seu prefixo de PENDENTES para LIGADOS. */
    static final List<String> LIGADOS = List.of();
    /** Gravados mas ainda sem implementacao. */
    static final List<String> PENDENTES = List.of("cmd_relatorio_repasse_", "cmd_auditar_repasses_",
            "cmd_marcar_repasse_transferido_", "cmd_adotar_pedidos_orfaos_", "cmd_purgar_dados_blog_customer_",
            "cmd_instituicoes_");
    /** Cenarios cujo comando grava no banco: o eco (que nao grava) tem de cair no estado final. */
    static final Set<String> GRAVAM = Set.of("cmd_marcar_repasse_transferido_confirmar.json",
            "cmd_adotar_pedidos_orfaos_vincula.json", "cmd_adotar_pedidos_orfaos_email.json",
            "cmd_purgar_dados_blog_customer_yes.json", "cmd_purgar_dados_blog_customer_prompt_confirmado.json");
    static final List<String> TABELAS = List.of("users_user", "token_blacklist_outstandingtoken",
            "token_blacklist_blacklistedtoken", "instituicoes_repasse", "payments_webhookevento",
            "freemium_freemiumconfirmationtoken", "orders_pedido", "plans_plan", "instituicoes_instituicao",
            "blog_reacao", "blog_comentario", "blog_customerbanido", "blog_postespelho");
    /** Os comandos cujo cenario leva o sha256 do relatorio_repasse.py de referencia (working tree do Django). */
    static final List<String> COM_REFERENCIA = List.of("relatorio_repasse", "marcar_repasse_transferido");
    /** Ate a Task 2 nao ha modo comando; nenhum grupo esta ligado, entao isto nunca roda. */
    static final ExecutorDeComando SEM_MODO_COMANDO = (args, stdin, dir) -> {
        throw new AssertionError("modo comando ainda nao existe: a Task 2 liga o executor");
    };
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UsuarioRepository usuarios;

    @Autowired
    ServicoDeTokens tokens;

    @Autowired
    RateLimiter limiter;

    @Autowired
    RelogioDeTeste.Fixavel relogio;

    @AfterEach
    void soltarRelogio() {
        relogio.fixar(null); // contexto compartilhado: o proximo teste nao herda o relogio congelado
    }

    static List<Path> gravados() throws Exception {
        try (Stream<Path> s = Files.list(CENARIOS)) {
            return s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    static Stream<Named<Path>> cenarios() throws Exception {
        List<Path> lista = gravados();
        // guarda: fonte vazia ou incompleta nao passa calada; o que nao roda esta pendente
        assertThat(lista).hasSize(GRAVADOS);
        assertThat(lista.stream().filter(p -> grupo(p, LIGADOS) || grupo(p, PENDENTES)).count()).isEqualTo(GRAVADOS);
        return lista.stream().filter(p -> grupo(p, LIGADOS)).map(p -> Named.of(p.getFileName().toString(), p));
    }

    static boolean grupo(Path p, List<String> prefixos) {
        return prefixos.stream().anyMatch(p.getFileName().toString()::startsWith);
    }

    /**
     * Cenario gravado nao pode sumir sem aviso: ou esta ligado, ou esta pendente (em um so), e todo prefixo tem cenario.
     * O prefixo e o do comando (cmd_<comando>_), e os cenarios do relatorio e da quitacao levam todos o mesmo sha256 da
     * referencia (regravar so parte deles depois de mudar o relatorio_repasse.py nao passa).
     */
    @Test
    void todoCenarioLigadoOuPendente() throws Exception {
        List<Path> lista = gravados();
        assertThat(lista).hasSize(GRAVADOS)
                .allSatisfy(p -> assertThat(grupo(p, LIGADOS)).as("%s", p).isNotEqualTo(grupo(p, PENDENTES)));
        Stream.concat(LIGADOS.stream(), PENDENTES.stream()).forEach(prefixo -> assertThat(lista)
                .as("prefixo %s", prefixo).anyMatch(p -> p.getFileName().toString().startsWith(prefixo)));
        Set<String> referencias = new java.util.HashSet<>();
        for (Path p : lista) {
            JsonNode c = JSON.readTree(p.toFile());
            String comando = c.get("comando").asString();
            assertThat(p.getFileName().toString()).as("comando de %s", p).startsWith("cmd_" + comando + "_");
            JsonNode sha = c.path("referencia_relatorio_repasse_sha256");
            assertThat(sha.isString()).as("sha256 da referencia em %s", p).isEqualTo(COM_REFERENCIA.contains(comando));
            if (sha.isString()) {
                referencias.add(sha.asString());
            }
        }
        assertThat(referencias).as("um relatorio_repasse.py so").hasSize(1);
    }

    @ParameterizedTest(name = "{0}", allowZeroInvocations = true)
    @MethodSource("cenarios")
    void cenario(Path json) throws Exception {
        replay(SEM_MODO_COMANDO).executar(json, null);
    }

    /**
     * O harness em todo cenario gravado: o banco semeado pelos "dados", o eco do gravado passa; nos que gravam no
     * banco, o eco (que nao grava) cai so no estado final, que e conferido depois do comando.
     */
    @Test
    void ecoDoGravadoPassaEmTodoCenario() throws Exception {
        for (Path json : gravados()) {
            Eco eco = new Eco(json);
            String nome = json.getFileName().toString();
            if (GRAVAM.contains(nome)) {
                assertThatThrownBy(() -> replay(eco).executar(json, null)).as(nome)
                        .isInstanceOf(AssertionError.class).hasMessageContaining(nome + ": estado final de ");
            } else {
                replay(eco).executar(json, null);
            }
        }
    }

    /**
     * Cada campo, num cenario que tem todos (auditar com achados: stdout com a linha do CSV, CommandError no stderr,
     * exit 1, CSV gravado, estado final): o eco passa, e cada divergencia falha no campo certo.
     */
    @Test
    void comparaCadaCampoEFalhaNaDivergencia() throws Exception {
        Path json = CENARIOS.resolve("cmd_auditar_repasses_com_achados_csv.json");
        String csv = "auditoria-ledger-2026-10-28.csv";

        Eco eco = new Eco(json);
        replay(eco).executar(json, null);
        // o executor recebe [comando, args...] com {saida} trocado pelo diretorio de trabalho, e o stdin gravado
        assertThat(eco.args).containsExactly("auditar_repasses", "--csv", eco.dir + "/" + csv);
        assertThat(eco.dir).startsWithRaw(Path.of("target").toAbsolutePath()).doesNotExist(); // apagado depois
        assertThat(eco.stdin).isNull();

        falha(json, e -> e.stdout = e.stdout.replace("R$ 22,00", "R$ 22,01"), "stdout");
        falha(json, e -> e.stdout = e.stdout.replace("\n\nCSV: ", "\nCSV: "), "stdout"); // uma linha vazia a menos
        falha(json, e -> e.stdout = e.stdout.replace("CSV: ", "CSV gravado: "), "stdout");
        passa(json, e -> e.stdout = e.stdout.replace(Eco.QUALQUER_CAMINHO, "/outro/lugar.csv")); // D10
        passa(json, e -> e.stdout = e.stdout.substring(0, e.stdout.length() - 1)); // sem o \n final: mesmas linhas
        falha(json, e -> e.stderr = "CommandError: 4 achado(s) crítico(s), R$ 22,00 em jogo.\n", "1a linha do stderr");
        falha(json, e -> e.stderr = "", "1a linha do stderr");
        passa(json, e -> e.stderr += "Traceback que o contrato nao olha\n"); // so a 1a linha
        falha(json, e -> e.exit = 0, "exit");
        falha(json, e -> e.exit = 2, "exit");
        falha(json, e -> e.arquivos.put(csv, semBom(e.arquivos.get(csv))), csv);
        falha(json, e -> e.arquivos.put(csv, new String(e.arquivos.get(csv), java.nio.charset.StandardCharsets.UTF_8)
                .replace("\r\n", "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)), csv);
        falha(json, e -> e.arquivos.remove(csv), "arquivos gravados");
        falha(json, e -> e.arquivos.put("sub/sobra.csv", new byte[] {1}), "arquivos gravados");
        falha(json, e -> e.efeito = () -> jdbc.update("UPDATE instituicoes_repasse SET comprovante = 'x'"),
                "estado final de instituicoes_repasse");
    }

    /** stderr null (traceback do Django, divergencia deliberada): qualquer stderr passa; o exit continua valendo. */
    @Test
    void stderrNullNaoCompara() throws Exception {
        Path json = CENARIOS.resolve("cmd_purgar_dados_blog_customer_duas_contas_mesma_caixa.json");
        assertThat(JSON.readTree(json.toFile()).get("stderr").isNull()).isTrue();
        passa(json, e -> e.stderr = "CommandError: Mais de uma conta com o e-mail 'ana@exemplo.com'.\n");
        falha(json, e -> e.exit = 0, "exit");
    }

    /** O stdin gravado chega ao executor, e o arquivo do nome padrao (caminho relativo) e procurado no dir de saida. */
    @Test
    void stdinENomePadrao() throws Exception {
        Path prompt = CENARIOS.resolve("cmd_purgar_dados_blog_customer_prompt_cancelado.json");
        Eco eco = new Eco(prompt);
        replay(eco).executar(prompt, null);
        assertThat(eco.stdin).isEqualTo("yes\n");
        assertThat(eco.args).containsExactly("purgar_dados_blog_customer", "beto@exemplo.com");

        Path padrao = CENARIOS.resolve("cmd_relatorio_repasse_nome_padrao.json");
        replay(new Eco(padrao)).executar(padrao, null);
        String nome = "relatorio-repasse-kms-pela-vida-|-nao-desista-2026-09-28_a_2026-10-27.csv";
        falha(padrao, e -> e.arquivos.put("relatorio.csv", e.arquivos.remove(nome)), "arquivos gravados");
    }

    /** cmd_instituicoes_: uma linha por instituicao; a contagem do shell -c e as linhas do stdout_java. */
    @Test
    void instituicoesContaLinhas() throws Exception {
        Path json = CENARIOS.resolve("cmd_instituicoes_contagem.json");
        JsonNode c = JSON.readTree(json.toFile());
        assertThat(ReplayDeContrato.textos(c.get("stdout"))).containsExactly("5");
        passa(json, e -> { });
        falha(json, e -> e.stdout = e.stdout.substring(e.stdout.indexOf('\n') + 1), "uma linha por instituicao");
        falha(json, e -> e.stdout = e.stdout.replace("Casa Fechada", "Casa Aberta"), "stdout");
        Path vazio = CENARIOS.resolve("cmd_instituicoes_nenhuma.json");
        passa(vazio, e -> { });
        falha(vazio, e -> e.stdout = "0\n", "uma linha por instituicao"); // o numero do Django nao serve
    }

    @Test
    void cenarioDeComandoExigeExecutor() {
        Path json = CENARIOS.resolve("cmd_instituicoes_nenhuma.json");
        assertThatThrownBy(() -> new ReplayDeContrato(jdbc, usuarios, tokens, limiter, TABELAS)
                .comRelogio(relogio::fixar).executar(json, null))
                .isInstanceOf(AssertionError.class).hasMessageContaining("use comComandos");
    }

    private void passa(Path json, java.util.function.Consumer<Eco> ajuste) throws Exception {
        Eco eco = new Eco(json);
        ajuste.accept(eco);
        replay(eco).executar(json, null);
    }

    private void falha(Path json, java.util.function.Consumer<Eco> ajuste, String campo) throws Exception {
        Eco eco = new Eco(json);
        ajuste.accept(eco);
        assertThatThrownBy(() -> replay(eco).executar(json, null)).as(campo).isInstanceOf(AssertionError.class)
                .hasMessageContaining(json.getFileName() + ": " + campo);
    }

    private static byte[] semBom(byte[] b) {
        assertThat(b).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        return java.util.Arrays.copyOfRange(b, 3, b.length);
    }

    private ReplayDeContrato replay(ExecutorDeComando executor) {
        return new ReplayDeContrato(jdbc, usuarios, tokens, limiter, TABELAS).comRelogio(relogio::fixar)
                .comComandos(executor);
    }

    /**
     * Executor falso: devolve o que o cenario gravou (as linhas de "stdout", ou de "stdout_java", com um caminho no
     * lugar do {@link ReplayDeContrato#CAMINHO}), grava os "arquivos" no diretorio de saida e guarda o que recebeu.
     * Os campos sao ajustaveis para provocar cada divergencia.
     */
    static final class Eco implements ExecutorDeComando {
        static final String QUALQUER_CAMINHO = "/tmp/qualquer/arquivo.csv";
        String stdout;
        String stderr;
        int exit;
        final Map<String, byte[]> arquivos = new LinkedHashMap<>();
        Runnable efeito = () -> { };
        List<String> args;
        String stdin;
        Path dir;

        Eco(Path json) throws Exception {
            JsonNode c = JSON.readTree(json.toFile());
            List<String> linhas = ReplayDeContrato.textos(c.has("stdout_java") ? c.get("stdout_java") : c.get("stdout"));
            stdout = linhas.stream().map(l -> l.replace(ReplayDeContrato.CAMINHO, QUALQUER_CAMINHO) + "\n")
                    .collect(Collectors.joining());
            stderr = c.get("stderr").isString() ? c.get("stderr").asString() + "\n" : "";
            exit = c.get("exit").asInt();
            c.get("arquivos").properties().forEach(a -> arquivos.put(a.getKey(),
                    Base64.getDecoder().decode(a.getValue().asString())));
        }

        @Override
        public Resultado executar(String[] args, String stdin, Path dirSaida) throws Exception {
            this.args = new ArrayList<>(List.of(args));
            this.stdin = stdin;
            this.dir = dirSaida;
            for (Map.Entry<String, byte[]> a : arquivos.entrySet()) {
                Path p = dirSaida.resolve(a.getKey());
                Files.createDirectories(p.getParent());
                Files.write(p, a.getValue());
            }
            efeito.run();
            return new Resultado(stdout, stderr, exit);
        }
    }
}
