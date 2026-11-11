package me.clama.catalogo;

/** O que os outros modulos enxergam de um plano. */
public record PlanoResumo(Long id, String nome, String descricao, long valorCentavos, Complexidade complexidade,
		TipoDePlano tipo) {}
