package me.clama.api.comum.agenda;

import java.util.List;
import me.clama.api.comum.comando.Chamada;
import me.clama.api.comum.comando.Comando;
import me.clama.api.comum.comando.Comandos.Opcao;
import me.clama.api.comum.comando.ErroDeComando;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * tarefa &lt;nome&gt; [--modo LISTAR|EXECUTAR] (mapa §9.7): roda uma tarefa agendada uma vez e imprime o resumo em JSON
 * numa linha. LISTAR por padrao (D13): EXECUTAR, que altera dados, so quando pedido. DESLIGADA nao e modo daqui.
 */
@Component
public class ComandoTarefa implements Comando {
    public static final List<Opcao> OPCOES = List.of(Opcao.posicional("nome"),
            Opcao.valor("--modo", "LISTAR", "EXECUTAR"));

    private final AgendaConfig agenda;
    private final ObjectMapper json;

    ComandoTarefa(AgendaConfig agenda, ObjectMapper json) {
        this.agenda = agenda;
        this.json = json;
    }

    @Override
    public int executar(Chamada c) {
        String nome = c.valor("nome");
        if (!agenda.nomes().contains(nome)) {
            throw new ErroDeComando("Tarefa agendada desconhecida: '" + nome + "'. Disponíveis: "
                    + String.join(", ", agenda.nomes()));
        }
        ModoDeExecucao modo = ModoDeExecucao.valueOf(c.opcoes().getOrDefault("--modo", "LISTAR"));
        c.out().println(json.writeValueAsString(agenda.executarAgora(nome, modo).resumo()));
        return 0;
    }
}
