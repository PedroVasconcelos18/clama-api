package me.clama.api.comum.agenda;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.clama.api.comum.comando.Comandos;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.TaskScheduler;
import tools.jackson.databind.ObjectMapper;

/** tarefa &lt;nome&gt; [--modo LISTAR|EXECUTAR] (mapa §9.7, D13): LISTAR por padrao, EXECUTAR so pedido. */
class ComandoTarefaTest {
    static final class Fake implements TarefaAgendada {
        final String nome;
        final List<ModoDeExecucao> modos = new ArrayList<>();

        Fake(String nome) {
            this.nome = nome;
        }

        public String nome() { return nome; }
        public boolean alteraDados() { return true; }

        public ResultadoDaTarefa executar(ModoDeExecucao modo) {
            modos.add(modo);
            Map<String, Object> resumo = new LinkedHashMap<>();
            resumo.put("modo", modo.name());
            resumo.put("n", 2);
            return new ResultadoDaTarefa(nome, modo, resumo);
        }
    }

    final Fake purgar = new Fake("purgar-ips");
    final Fake reconciliar = new Fake("reconciliar-freemium");
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int tarefa(String... args) {
        out.reset();
        err.reset();
        MockEnvironment env = new MockEnvironment().withProperty("clama.agenda.purgar-ips.cron", "0 0 3 * * *")
                .withProperty("clama.agenda.reconciliar-freemium.cron", "0 17 */6 * * *");
        AgendaConfig agenda = new AgendaConfig(List.of(reconciliar, purgar), env, mock(TaskScheduler.class));
        try (GenericApplicationContext ctx = new GenericApplicationContext()) {
            ctx.registerBean(ComandoTarefa.class, () -> new ComandoTarefa(agenda, new ObjectMapper()));
            ctx.refresh();
            String[] linha = new String[args.length + 1];
            linha[0] = "tarefa";
            System.arraycopy(args, 0, linha, 1, args.length);
            return Comandos.executar(linha, new PrintStream(out, true, UTF_8), new PrintStream(err, true, UTF_8),
                    new BufferedReader(new StringReader("")), Path.of("."), ctx);
        }
    }

    @Test
    void semModoListaEImprimeOResumoEmJsonNumaLinha() {
        assertThat(tarefa("purgar-ips")).isZero();
        assertThat(purgar.modos).containsExactly(ModoDeExecucao.LISTAR);
        assertThat(out.toString(UTF_8)).isEqualTo("{\"modo\":\"LISTAR\",\"n\":2}\n");
        assertThat(reconciliar.modos).isEmpty();
    }

    @Test
    void executarSoQuandoPedido() {
        assertThat(tarefa("purgar-ips", "--modo", "EXECUTAR")).isZero();
        assertThat(tarefa("purgar-ips", "--modo=LISTAR")).isZero();
        assertThat(purgar.modos).containsExactly(ModoDeExecucao.EXECUTAR, ModoDeExecucao.LISTAR);
        assertThat(out.toString(UTF_8)).isEqualTo("{\"modo\":\"LISTAR\",\"n\":2}\n");
    }

    @Test
    void nomeDesconhecidoECommandErrorComAsDisponiveis() {
        assertThat(tarefa("purgar")).isEqualTo(1);
        assertThat(err.toString(UTF_8)).isEqualTo(
                "CommandError: Tarefa agendada desconhecida: 'purgar'. Disponíveis: reconciliar-freemium, purgar-ips\n");
        assertThat(out.toString(UTF_8)).isEmpty();
        assertThat(purgar.modos).isEmpty();
    }

    @Test
    void desligadaNaoEModoESemNomeEUso() {
        assertThat(tarefa("purgar-ips", "--modo", "DESLIGADA")).isEqualTo(2);
        assertThat(err.toString(UTF_8)).contains("tarefa: error: argument --modo: invalid choice: 'DESLIGADA'");
        assertThat(tarefa()).isEqualTo(2);
        assertThat(err.toString(UTF_8)).contains("tarefa: error: the following arguments are required: nome");
        assertThat(purgar.modos).isEmpty();
    }
}
