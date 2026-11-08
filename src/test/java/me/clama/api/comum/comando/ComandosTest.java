package me.clama.api.comum.comando;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.sentry.Sentry;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import me.clama.api.comum.comando.Comandos.Definicao;
import me.clama.api.comum.comando.Comandos.Opcao;
import me.clama.api.comum.erro.PastoralException;
import me.clama.api.comum.observabilidade.TransportEmMemoria;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

/** Parser e exit codes do modo comando (mapa §9.2, §9.5), sem banco: o contexto e um GenericApplicationContext. */
class ComandosTest {
    static final Definicao EXEMPLO = new Definicao("exemplo", Falso.class, List.of(Opcao.posicional("email"),
            Opcao.valor("--saida"), Opcao.valor("--modo", "LISTAR", "EXECUTAR"), Opcao.flag("--dry-run")));
    static final String USO = "usage: java -jar clama-api.jar exemplo [--saida SAIDA] [--modo {LISTAR,EXECUTAR}]"
            + " [--dry-run] email";

    final TransportEmMemoria sentry = new TransportEmMemoria();
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();
    final Path dir = Path.of("/tmp/saida-do-comando");

    @BeforeEach
    void abre() {
        Sentry.init(o -> {
            o.setDsn("https://chave@localhost/1");
            o.setTransportFactory(sentry.fabrica());
        });
    }

    @AfterEach
    void fecha() {
        Sentry.close();
    }

    @Test
    void leValorSeparadoOuComIgualFlagEPosicionalUltimaVence() throws Exception {
        assertThat(Comandos.ler(EXEMPLO, List.of("--saida", "a.csv", "ana@exemplo.com", "--dry-run", "--saida=b.csv",
                "--modo", "EXECUTAR"))).isEqualTo(Map.of("email", "ana@exemplo.com", "--saida", "b.csv",
                "--dry-run", "true", "--modo", "EXECUTAR"));
        assertThat(Comandos.ler(EXEMPLO, List.of("x"))).isEqualTo(Map.of("email", "x")); // opcional ausente: sem chave
        assertThat(Comandos.ler(EXEMPLO, List.of("x", "--saida=a=b"))).containsEntry("--saida", "a=b");
        assertThat(Comandos.ler(EXEMPLO, List.of("x", "--saida", "-5"))).containsEntry("--saida", "-5");
    }

    @Test
    void erroDeUsoVaiProStderrComExit2SemAbrirContexto() {
        uso(List.of(), "the following arguments are required: email");
        uso(List.of("a", "b"), "unrecognized arguments: b");
        uso(List.of("a", "--saidaa", "x"), "unrecognized arguments: --saidaa x"); // sem abreviacao (desvio aceito)
        uso(List.of("a", "--saida"), "argument --saida: expected one argument");
        uso(List.of("a", "--saida", "--dry-run"), "argument --saida: expected one argument");
        uso(List.of("a", "--modo", "DESLIGADA"), "argument --modo: invalid choice: 'DESLIGADA' (choose from LISTAR, EXECUTAR)");
        uso(List.of("a", "--dry-run=sim"), "argument --dry-run: ignored explicit argument 'sim'");
    }

    /** required=True do argparse: sem a opcao e uso (exit 2), na ordem da definicao; no usage, sem colchetes. */
    @Test
    void opcaoObrigatoria() throws Exception {
        Definicao d = new Definicao("rel", Falso.class, List.of(Opcao.obrigatoria("--instituicao"),
                Opcao.posicional("email"), Opcao.flag("--auditoria")));
        assertThat(Comandos.ler(d, List.of("x", "--instituicao=Casa"))).containsEntry("--instituicao", "Casa");
        assertThatThrownBy(() -> Comandos.ler(d, List.of("--auditoria"))).isInstanceOf(Comandos.ErroDeUso.class)
                .hasMessage("the following arguments are required: --instituicao, email");
        assertThatThrownBy(() -> Comandos.ler(d, List.of("x"))).hasMessage(
                "the following arguments are required: --instituicao");
        assertThat(Comandos.uso(d)).isEqualTo("usage: java -jar clama-api.jar rel --instituicao INSTITUICAO"
                + " [--auditoria] email");
    }

    private void uso(List<String> args, String erro) {
        out.reset();
        err.reset();
        String[] linha = java.util.stream.Stream.concat(java.util.stream.Stream.of("exemplo"), args.stream())
                .toArray(String[]::new);
        // contexto null: se passasse do parse, abriria o Spring de verdade (e falharia sem banco)
        assertThat(executar(linha, null)).as("%s", args).isEqualTo(2);
        assertThat(err.toString(UTF_8)).isEqualTo(USO + "\nexemplo: error: " + erro + "\n");
        assertThat(out.toString(UTF_8)).isEmpty();
    }

    @Test
    void comandoDesconhecidoEExit2() {
        assertThat(Comandos.executar(new String[] {"relatorio_repase"}, saida(out), saida(err), stdin(null), dir, null))
                .isEqualTo(2);
        assertThat(err.toString(UTF_8)).startsWith("Comando desconhecido: 'relatorio_repase'. Disponíveis: ")
                .contains("instituicoes, tarefa, relatorio_repasse, auditar_repasses, marcar_repasse_transferido");
    }

    @Test
    void okDevolveOExitDoComandoComStdoutUtf8StdinEDiretorio() {
        int exit = executar(new String[] {"exemplo", "--saida", "rel.csv", "ação@exemplo.com"}, c -> {
            c.out().println("Associação " + c.valor("email") + " " + c.caminho(c.valor("--saida")) + " "
                    + c.caminho("/abs/x.csv") + " " + c.in().readLine() + " " + c.in().readLine());
            return 0;
        }, "não\n");
        assertThat(exit).isZero();
        assertThat(out.toString(UTF_8))
                .isEqualTo("Associação ação@exemplo.com /tmp/saida-do-comando/rel.csv /abs/x.csv não null\n");
        assertThat(err.toString(UTF_8)).isEmpty();
        assertThat(executar(new String[] {"exemplo", "x"}, c -> 1, null)).isEqualTo(1);
        assertThat(sentry.eventos).isEmpty();
    }

    @Test
    void erroDeComandoEPastoralViramCommandErrorComExit1SemSentry() {
        assertThat(executar(new String[] {"exemplo", "x"}, c -> {
            c.out().println("antes");
            throw new ErroDeComando("Nenhuma instituição casa com 'X'.");
        }, null)).isEqualTo(1);
        assertThat(err.toString(UTF_8)).isEqualTo("CommandError: Nenhuma instituição casa com 'X'.\n");
        assertThat(out.toString(UTF_8)).isEqualTo("antes\n");

        err.reset();
        assertThat(executar(new String[] {"exemplo", "x"}, c -> {
            throw new PastoralException(400, "x", "msg tecnica", "Mensagem pastoral.");
        }, null)).isEqualTo(1);
        assertThat(err.toString(UTF_8)).isEqualTo("CommandError: Mensagem pastoral.\n");
        assertThat(sentry.eventos).isEmpty();
    }

    @Test
    void excecaoInesperadaTracebackExit1ESentryComTagDoComando() {
        assertThat(executar(new String[] {"exemplo", "x"}, c -> {
            throw new IllegalStateException("boom");
        }, null)).isEqualTo(1);
        assertThat(err.toString(UTF_8)).startsWith("java.lang.IllegalStateException: boom\n\tat ");
        assertThat(sentry.eventos).singleElement().satisfies(e -> {
            assertThat(e.getTag("comando")).isEqualTo("exemplo");
            assertThat(e.getExceptions()).extracting(x -> x.getValue()).contains("boom");
        });
    }

    @Test
    void stdinNuloEEof() throws Exception {
        assertThat(stdin(null).readLine()).isNull();
    }

    @Test
    void definicaoDuplicadaNaoSobe() {
        assertThatThrownBy(() -> Comandos.porNome(EXEMPLO, EXEMPLO)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exemplo");
    }

    @FunctionalInterface
    interface Corpo {
        int executar(Chamada c) throws Exception;
    }

    /** O bean do comando de exemplo: faz o que o teste mandar. */
    static final class Falso implements Comando {
        final Corpo corpo;

        Falso(Corpo corpo) {
            this.corpo = corpo;
        }

        @Override
        public int executar(Chamada c) throws Exception {
            return corpo.executar(c);
        }
    }

    private int executar(String[] args, Corpo corpo, String entrada) {
        try (GenericApplicationContext ctx = new GenericApplicationContext()) {
            ctx.registerBean(Falso.class, () -> new Falso(corpo));
            ctx.refresh();
            return executar(args, ctx, entrada);
        }
    }

    private int executar(String[] args, GenericApplicationContext ctx) {
        return executar(args, ctx, null);
    }

    private int executar(String[] args, GenericApplicationContext ctx, String entrada) {
        return Comandos.executar(Comandos.porNome(EXEMPLO), args, saida(out), saida(err), stdin(entrada), dir, ctx);
    }

    static PrintStream saida(ByteArrayOutputStream b) {
        return new PrintStream(b, true, UTF_8);
    }

    static BufferedReader stdin(String texto) {
        return new BufferedReader(new StringReader(texto == null ? "" : texto));
    }
}
