package me.clama.catalogo.web;

/**
 * {@code logoUrl}: caminho com a versao (8 primeiros do sha256) para o cache imutavel; nulo sem logo. Os quatro campos de
 * meta sao opcionais (sem meta, o front nao mostra a barra).
 */
public record InstituicaoPublica(Long id, String nome, String logoUrl, String causa, Long metaMensalCentavos,
		Long custoUnidadeCentavos, String unidade) {

	static String urlDaLogo(Long id, String sha256) {
		return sha256 == null ? null : "/api/instituicoes/" + id + "/logo?v=" + sha256.substring(0, 8);
	}
}
