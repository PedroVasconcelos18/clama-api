package me.clama.conta;

/**
 * O que os outros modulos enxergam de um usuario. {@code nomePublico}: o nome exposto no blog; {@code admin}: papel
 * diferente de CLIENTE; {@code papel}: CLIENTE, ADMIN ou SUPERUSUARIO.
 */
public record UsuarioResumo(Long id, String email, String nomeCompleto, String nomePublico, boolean admin, String papel) {}
