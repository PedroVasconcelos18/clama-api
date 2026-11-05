package me.clama.api.comum.retry;

/** Resposta HTTP nao-2xx (equivale ao requests.HTTPError). */
public class HttpStatusException extends RuntimeException {
    private final int status;

    public HttpStatusException(int status) {
        this(status, null);
    }

    /** Corpo da resposta na mensagem, cortado em 500 caracteres (a redacao do Sentry tira os e-mails). */
    public HttpStatusException(int status, String corpo) {
        super(corpo == null || corpo.isEmpty() ? "HTTP " + status
                : "HTTP " + status + ": " + corpo.substring(0, Math.min(corpo.length(), 500)));
        this.status = status;
    }

    public int status() {
        return status;
    }
}
