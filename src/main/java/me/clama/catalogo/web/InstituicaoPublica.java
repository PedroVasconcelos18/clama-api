package me.clama.catalogo.web;

/** {@code logoUrl}: caminho com a versao (8 primeiros do sha256) para o cache imutavel; nulo sem logo. */
public record InstituicaoPublica(Long id, String nome, String logoUrl) {}
