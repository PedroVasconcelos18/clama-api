package me.clama.api.oracao;

import me.clama.api.comum.texto.TextoPython;
import me.clama.api.pedido.Pedido;
import me.clama.api.prompt.PromptTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** build_prompt de clama/prayer_generation/services/prompt_builder.py; o system prompt e o do template, cru. */
public final class MontagemDoPrompt {
    private static final ObjectMapper JSON = new ObjectMapper();

    private MontagemDoPrompt() {}

    /**
     * A mensagem do usuario, exatamente como o f-string do Django. Nome completo; sexo vazio e idade 0 contam como
     * ausentes (falsy); pedido com strip() do Python, vazio vira "[pedido vazio]".
     */
    public static String mensagemDoUsuario(Pedido p, PromptTemplate t) {
        // instrucoes_por_complexidade.get(complexidade, ""): valor nao-texto sai como o str() do Python
        JsonNode instrucao = JSON.readTree(t.getInstrucoesPorComplexidade()).get(p.getPlano().getComplexidade());
        String pedido = p.getPedidoOracao() == null ? "" : TextoPython.strip(p.getPedidoOracao());
        String sexo = p.getSexo() == null || p.getSexo().isEmpty() ? "não informado" : p.getSexo();
        String idade = p.getIdade() == null || p.getIdade() == 0 ? "não informada" : p.getIdade().toString();
        return (instrucao == null ? "" : TextoPython.str(instrucao)) + "\n\nNome: " + p.getNome() + "\nSexo: " + sexo
                + "\nIdade: " + idade + "\n\nPedido: " + (pedido.isEmpty() ? "[pedido vazio]" : pedido);
    }
}
