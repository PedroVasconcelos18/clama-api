package me.clama.pedido;

/** O que o admin pode corrigir num pedido (D22); nulo = deixa como esta, telefone em branco = apaga. Sem toString: e PII. */
public record CorrecaoDeDados(String nome, String email, String telefone, Short idade, String sexo, String pedidoOracao) {

	@Override
	public String toString() {
		return "CorrecaoDeDados[...]";
	}
}
