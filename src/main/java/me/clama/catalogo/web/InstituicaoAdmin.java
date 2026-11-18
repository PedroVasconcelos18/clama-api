package me.clama.catalogo.web;

import me.clama.catalogo.interno.Instituicao;

/** {@code logoUrl} no mesmo formato da lista publica; nulo (e {@code temLogo} falso) sem logo. */
public record InstituicaoAdmin(Long id, String nome, int ordem, boolean ativo, String logoUrl, boolean temLogo, String causa,
		Long metaMensalCentavos, Long custoUnidadeCentavos, String unidade) {

	static InstituicaoAdmin de(Instituicao i, String sha256) {
		return new InstituicaoAdmin(i.getId(), i.getNome(), i.getOrdem(), i.isAtivo(),
				InstituicaoPublica.urlDaLogo(i.getId(), sha256), sha256 != null, i.getCausa(), i.getMetaMensalCentavos(),
				i.getCustoUnidadeCentavos(), i.getUnidade());
	}
}
