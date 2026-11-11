package me.clama.pedido;

/** Nome e e-mail decifrados do doador: so para o relatorio interno e a versao abreviada do relatorio da instituicao. Nunca em log. */
public record Doador(String nome, String email) {

	@Override
	public String toString() {
		return "Doador[...]";
	}
}
