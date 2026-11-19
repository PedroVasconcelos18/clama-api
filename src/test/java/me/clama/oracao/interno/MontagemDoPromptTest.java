package me.clama.oracao.interno;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import me.clama.pedido.DadosParaOracao;
import me.clama.pedido.StatusPedido;
import org.junit.jupiter.api.Test;

/** A mensagem do usuario no formato antigo; desde a v2 a instrucao e a mesma para todos os planos (spec 8 P2). */
class MontagemDoPromptTest {

	static final Prompt PROMPT = new Prompt("Você é o Clama.", "Curta.", "Com versículo.", "Com profecia.", "Gratuita.");

	@Test
	void pedidoCompleto() {
		assertThat(MontagemDoPrompt.mensagem(PROMPT, dados("Maria Silva", "feminino", (short) 35, null,
				"  Peço oração pela minha família.\n"))).isEqualTo("""
				Com versículo.

				Nome: Maria Silva
				Sexo: feminino
				Idade: 35

				Pedido: Peço oração pela minha família.""");
	}

	/** Sexo vazio ou nulo, idade nula ou 0 (falsy no Django) e pedido so de espaco. */
	@Test
	void opcionaisVazios() {
		for (var sexo : new String[] { null, "" }) {
			assertThat(MontagemDoPrompt.mensagem(PROMPT, dados("Ana", sexo, sexo == null ? null : (short) 0, null, " 　\t ")))
					.isEqualTo("Com versículo.\n\nNome: Ana\nSexo: não informado\nIdade: não informada\n\nPedido: [pedido vazio]");
		}
		assertThat(MontagemDoPrompt.mensagem(PROMPT, dados("Ana", "masculino", null, null, null)))
				.startsWith("Com versículo.\n\nNome: Ana\nSexo: masculino\n").endsWith("Pedido: [pedido vazio]");
	}

	/** "Prefiro não informar" do formulario vai cru, como o build_prompt do Django manda em producao. */
	@Test
	void naoInformadoDoFormularioVaiCru() {
		assertThat(MontagemDoPrompt.mensagem(PROMPT, dados("Ana", "nao_informado", null, null, "paz")))
				.isEqualTo("Com versículo.\n\nNome: Ana\nSexo: nao_informado\nIdade: não informada\n\nPedido: paz");
	}

	@Test
	void usaSempreAInstrucaoComVersiculo() {
		// a mensagem nao recebe mais o plano: gratuito ou pago, a instrucao e a mesma
		assertThat(MontagemDoPrompt.mensagem(PROMPT, dados("Ana", null, null, null, "paz"))).startsWith(PROMPT.comVersiculo())
				.doesNotContain(PROMPT.simples()).doesNotContain(PROMPT.gratuito());
	}

	@Test
	void temaEntraDepoisDaIdade() {
		assertThat(MontagemDoPrompt.mensagem(PROMPT, dados("Ana", null, null, "luto", "paz")))
				.contains("\nIdade: não informada\nTema: Luto\n\nPedido: ");
		assertThat(MontagemDoPrompt.mensagem(PROMPT, dados("Ana", null, null, null, "paz"))).doesNotContain("Tema:");
	}

	static DadosParaOracao dados(String nome, String sexo, Short idade, String tema, String pedido) {
		return new DadosParaOracao(UUID.randomUUID(), StatusPedido.PAGO, 1L, false, nome, "maria@exemplo.com", idade, sexo,
				pedido, 7L, tema, null, 0, null);
	}
}
