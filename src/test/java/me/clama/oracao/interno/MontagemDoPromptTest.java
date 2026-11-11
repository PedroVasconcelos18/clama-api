package me.clama.oracao.interno;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import me.clama.catalogo.Complexidade;
import me.clama.catalogo.PlanoResumo;
import me.clama.catalogo.TipoDePlano;
import me.clama.pedido.DadosParaOracao;
import me.clama.pedido.StatusPedido;
import org.junit.jupiter.api.Test;

/** INV:107: a mensagem do usuario no formato antigo; a instrucao segue o plano do pedido (tipo e complexidade). */
class MontagemDoPromptTest {

	static final Prompt PROMPT = new Prompt("Você é o Clama.", "Curta.", "Com versículo.", "Com profecia.", "Gratuita.");

	@Test
	void pedidoCompleto() {
		assertThat(MontagemDoPrompt.mensagem(PROMPT, dados(false, "Maria Silva", "feminino", (short) 35,
				"  Peço oração pela minha família.\n"), plano(Complexidade.COM_VERSICULO, TipoDePlano.COMUM))).isEqualTo("""
				Com versículo.

				Nome: Maria Silva
				Sexo: feminino
				Idade: 35

				Pedido: Peço oração pela minha família.""");
	}

	/** Sexo vazio, nulo ou "nao_informado", idade nula ou 0 (falsy no Django, como no antigo) e pedido so de espaco. */
	@Test
	void opcionaisVazios() {
		for (var sexo : new String[] { null, "", "nao_informado" }) {
			assertThat(MontagemDoPrompt.mensagem(PROMPT, dados(false, "Ana", sexo, sexo == null ? null : (short) 0," 　\t "),
					plano(Complexidade.SIMPLES, TipoDePlano.COMUM)))
					.isEqualTo("Curta.\n\nNome: Ana\nSexo: não informado\nIdade: não informada\n\nPedido: [pedido vazio]");
		}
		assertThat(MontagemDoPrompt.mensagem(PROMPT, dados(false, "Ana", "masculino", null, null),
				plano(Complexidade.COM_PROFECIA_E_VERSICULOS, TipoDePlano.VALOR_LIVRE)))
				.startsWith("Com profecia.\n\nNome: Ana\nSexo: masculino\n").endsWith("Pedido: [pedido vazio]");
	}

	/** So o plano GRATUITO usa a instrucao gratuita, qualquer que seja a complexidade gravada nele. */
	@Test
	void planoGratuitoUsaAInstrucaoGratuita() {
		assertThat(MontagemDoPrompt.mensagem(PROMPT, dados(true, "Ana", null, null, "paz"),
				plano(Complexidade.COM_VERSICULO, TipoDePlano.GRATUITO))).startsWith("Gratuita.\n\nNome: Ana");
	}

	/** Pedido tornado gratuito pelo admin recebe a oracao do plano escolhido: a marca gratuito nao muda o prompt. */
	@Test
	void marcaGratuitoNaoMudaOPrompt() {
		assertThat(MontagemDoPrompt.mensagem(PROMPT, dados(true, "Ana", null, null, "paz"),
				plano(Complexidade.COM_PROFECIA_E_VERSICULOS, TipoDePlano.COMUM))).startsWith("Com profecia.\n\n");
	}

	static DadosParaOracao dados(boolean gratuito, String nome, String sexo, Short idade, String pedido) {
		return new DadosParaOracao(UUID.randomUUID(), StatusPedido.PAGO, 1L, gratuito, nome, "maria@exemplo.com", idade, sexo,
				pedido, null, 0, null);
	}

	static PlanoResumo plano(Complexidade c, TipoDePlano t) {
		return new PlanoResumo(1L, "Plano", null, 3000, c, t);
	}
}
