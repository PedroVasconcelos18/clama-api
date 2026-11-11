package me.clama.catalogo.interno;

/** Plano GRATUITO/VALOR_LIVRE so aceita mudar nome e descricao. */
public class PlanoTecnicoException extends IllegalStateException {

	public PlanoTecnicoException(String mensagem) {
		super(mensagem);
	}
}
