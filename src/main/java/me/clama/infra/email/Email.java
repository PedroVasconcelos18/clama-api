package me.clama.infra.email;

/** Um e-mail pronto para sair: texto puro sempre, html opcional. */
public record Email(String para, String assunto, String texto, String html) {}
