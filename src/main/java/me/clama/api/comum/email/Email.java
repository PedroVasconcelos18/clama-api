package me.clama.api.comum.email;

/** Remetente e reply-to saem da configuracao. {@code html} e nulo quando nao ha. */
public record Email(String para, String assunto, String texto, String html) {}
