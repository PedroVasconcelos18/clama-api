package me.clama.catalogo.web;

import me.clama.catalogo.Complexidade;

/** Vitrine de planos: so o que o front le (o valor em reais ele formata com Intl). */
public record PlanoPublico(Long id, String nome, String descricao, long valorCentavos, Complexidade complexidade) {}
