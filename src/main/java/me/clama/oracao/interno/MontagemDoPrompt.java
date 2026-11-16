package me.clama.oracao.interno;

import me.clama.catalogo.Complexidade;
import me.clama.catalogo.PlanoResumo;
import me.clama.catalogo.TipoDePlano;
import me.clama.pedido.DadosParaOracao;

/**
 * A mensagem do usuario no formato antigo (INV:107, build_prompt do Django): a instrucao do plano, nome, sexo, idade e o
 * pedido. Tem PII em claro: nao logar.
 */
public final class MontagemDoPrompt {

	private MontagemDoPrompt() {}

	public static String mensagem(Prompt prompt, DadosParaOracao d, PlanoResumo plano) {
		return mensagem(prompt, plano.complexidade(), plano.tipo(), d.nome(), d.sexo(), d.idade(), d.pedidoOracao());
	}

	/**
	 * Igual ao build_prompt do Django: sexo vazio vira "não informado" (o "nao_informado" do formulario vai cru, como em
	 * producao), idade nula ou 0 vira "não informada", pedido aparado e vazio vira "[pedido vazio]".
	 */
	public static String mensagem(Prompt prompt, Complexidade complexidade, TipoDePlano tipo, String nome, String sexo,
			Short idade, String pedido) {
		var texto = pedido == null ? "" : pedido.strip();
		var s = sexo == null || sexo.isEmpty() ? "não informado" : sexo;
		return prompt.instrucao(complexidade, tipo) + "\n\nNome: " + (nome == null ? "" : nome) + "\nSexo: " + s
				+ "\nIdade: " + (idade == null || idade == 0 ? "não informada" : idade) + "\n\nPedido: " + (texto.isEmpty() ? "[pedido vazio]" : texto);
	}
}
