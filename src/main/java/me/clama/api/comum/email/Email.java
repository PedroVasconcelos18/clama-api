package me.clama.api.comum.email;

/**
 * Remetente e reply-to saem da configuracao. {@code html} e nulo quando nao ha.
 *
 * <p>{@code chaveDeIdempotencia} (opcional) vira o header {@code Idempotency-Key} da Resend: a mesma chave em 24 h
 * nao reenvia. Use uma chave estavel por e-mail logico (ex.: {@code oracao/<pedidoId>}) e o mesmo conteudo nas
 * retentativas; a Resend devolve 409 se a chave voltar com outro payload.
 */
public record Email(String para, String assunto, String texto, String html, String chaveDeIdempotencia) {
    public Email {
        if (chaveDeIdempotencia != null && (chaveDeIdempotencia.isEmpty() || chaveDeIdempotencia.length() > 256)) {
            throw new IllegalArgumentException("chave de idempotencia precisa ter de 1 a 256 caracteres");
        }
    }

    public Email(String para, String assunto, String texto, String html) {
        this(para, assunto, texto, html, null);
    }

    public Email comChave(String chave) {
        return new Email(para, assunto, texto, html, chave);
    }
}
