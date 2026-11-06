package me.clama.api.oracao;

import static org.assertj.core.api.Assertions.assertThat;

import me.clama.api.catalogo.plano.Plano;
import me.clama.api.pedido.Pedido;
import me.clama.api.prompt.PromptTemplate;
import org.junit.jupiter.api.Test;

/** build_prompt de clama/prayer_generation/services/prompt_builder.py. */
class MontagemDoPromptTest {
    static final String INSTRUCOES = """
            {"simples": "Escreva uma oração curta.", "com_versiculo": "Inclua um versículo."}""";

    @Test
    void pedidoCompleto() {
        assertThat(MontagemDoPrompt.mensagemDoUsuario(pedido("com_versiculo", "Maria Silva", "feminino", 35,
                "  Peço oração pela minha família.\n"), template())).isEqualTo("""
                Inclua um versículo.

                Nome: Maria Silva
                Sexo: feminino
                Idade: 35

                Pedido: Peço oração pela minha família.""");
    }

    /** sexo vazio, idade 0 (falsy no Python) e pedido so de espaco; complexidade sem instrucao vira "". */
    @Test
    void opcionaisVaziosEComplexidadeSemInstrucao() {
        assertThat(MontagemDoPrompt.mensagemDoUsuario(pedido("simples_gratuita", "Ana", "", 0, " 　\t "),
                template())).isEqualTo("\n\nNome: Ana\nSexo: não informado\nIdade: não informada\n\nPedido: [pedido vazio]");
        assertThat(MontagemDoPrompt.mensagemDoUsuario(pedido("simples", "Ana", null, null, null), template()))
                .isEqualTo("Escreva uma oração curta.\n\nNome: Ana\nSexo: não informado\nIdade: não informada"
                        + "\n\nPedido: [pedido vazio]");
    }

    static Pedido pedido(String complexidade, String nome, String sexo, Integer idade, String texto) {
        Plano plano = new Plano();
        plano.setComplexidade(complexidade);
        Pedido p = new Pedido();
        p.setPlano(plano);
        p.setNome(nome);
        p.setSexo(sexo);
        p.setIdade(idade);
        p.setPedidoOracao(texto);
        return p;
    }

    static PromptTemplate template() {
        PromptTemplate t = new PromptTemplate();
        t.setSystemPrompt("Você é o Clama.");
        t.setInstrucoesPorComplexidade(INSTRUCOES);
        return t;
    }
}
