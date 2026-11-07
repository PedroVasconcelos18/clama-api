package me.clama.api.oracao;

import java.util.Map;
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
        String pedido = p.getPedidoOracao() == null ? "" : TextoPython.strip(p.getPedidoOracao());
        String sexo = p.getSexo() == null || p.getSexo().isEmpty() ? "não informado" : p.getSexo();
        String idade = p.getIdade() == null || p.getIdade() == 0 ? "não informada" : p.getIdade().toString();
        return instrucao(t, p.getPlano().getComplexidade()) + "\n\nNome: " + p.getNome() + "\nSexo: " + sexo
                + "\nIdade: " + idade + "\n\nPedido: " + (pedido.isEmpty() ? "[pedido vazio]" : pedido);
    }

    /** instrucoes_por_complexidade.get(complexidade, ""): valor nao-texto sai como o str() do Python. */
    private static String instrucao(PromptTemplate t, String complexidade) {
        JsonNode i = JSON.readTree(t.getInstrucoesPorComplexidade()).get(complexidade);
        return i == null ? "" : TextoPython.str(i);
    }

    /**
     * build_prompt_for_preview: os valores de {@code exemplo} ja vem aparados e sem branco (CharField); ausente cai no
     * default do Django. Idade sempre "não informada".
     */
    public static String mensagemDePrevia(Map<String, String> exemplo, PromptTemplate t) {
        return instrucao(t, exemplo.getOrDefault("plano_complexidade", "simples")) + "\n\nNome: "
                + exemplo.getOrDefault("nome", "Maria") + "\nSexo: " + exemplo.getOrDefault("sexo", "feminino")
                + "\nIdade: não informada\n\nPedido: " + exemplo.getOrDefault("pedido_oracao", "Oração de teste");
    }
}
