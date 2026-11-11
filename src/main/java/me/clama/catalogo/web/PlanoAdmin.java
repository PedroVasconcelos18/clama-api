package me.clama.catalogo.web;

import me.clama.catalogo.Complexidade;
import me.clama.catalogo.TipoDePlano;
import me.clama.catalogo.interno.Plano;

/** {@code tipo} diferente de COMUM = plano do sistema: a tela so deixa editar nome e descricao. */
public record PlanoAdmin(Long id, String nome, String descricao, long valorCentavos, Complexidade complexidade,
		TipoDePlano tipo, int ordem, boolean ativo) {

	static PlanoAdmin de(Plano p) {
		return new PlanoAdmin(p.getId(), p.getNome(), p.getDescricao(), p.getValorCentavos(), p.getComplexidade(),
				p.getTipo(), p.getOrdem(), p.isAtivo());
	}
}
