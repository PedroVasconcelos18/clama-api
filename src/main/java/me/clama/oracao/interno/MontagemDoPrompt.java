package me.clama.oracao.interno;

import me.clama.pedido.DadosParaOracao;

/**
 * A mensagem do usuario: a instrucao com versiculo (a mesma para todos desde a v2), nome, sexo, idade, tema e o pedido.
 * Tem PII em claro: nao logar.
 */
public final class MontagemDoPrompt {

	private MontagemDoPrompt() {}

	public static String mensagem(Prompt prompt, DadosParaOracao d) {
		return mensagem(prompt, d.nome(), d.sexo(), d.idade(), d.tema(), d.pedidoOracao());
	}

	/**
	 * Igual ao build_prompt do Django, mais o tema: sexo vazio vira "não informado" (o "nao_informado" do formulario vai cru,
	 * como em producao), idade nula ou 0 vira "não informada", pedido aparado e vazio vira "[pedido vazio]". Sem tema, sem a linha.
	 */
	public static String mensagem(Prompt prompt, String nome, String sexo, Short idade, String tema, String pedido) {
		var texto = pedido == null ? "" : pedido.strip();
		var s = sexo == null || sexo.isEmpty() ? "não informado" : sexo;
		return prompt.comVersiculo() + "\n\nNome: " + (nome == null ? "" : nome) + "\nSexo: " + s
				+ "\nIdade: " + (idade == null || idade == 0 ? "não informada" : idade)
				+ (tema == null ? "" : "\nTema: " + rotulo(tema)) + "\n\nPedido: " + (texto.isEmpty() ? "[pedido vazio]" : texto);
	}

	private static String rotulo(String tema) {
		return switch (tema) {
			case "luto" -> "Luto";
			case "ansiedade" -> "Ansiedade";
			case "familia" -> "Família";
			case "saude" -> "Saúde";
			case "sustento" -> "Trabalho e sustento";
			case "fe" -> "Fé cansada";
			case "injustica" -> "Injustiça";
			default -> throw new IllegalArgumentException("tema desconhecido");
		};
	}
}
