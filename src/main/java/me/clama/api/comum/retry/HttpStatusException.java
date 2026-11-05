package me.clama.api.comum.retry;

/** Resposta HTTP nao-2xx (equivale ao requests.HTTPError). */
public class HttpStatusException extends RuntimeException {
    private final int status;

    public HttpStatusException(int status) {
        super("HTTP " + status);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
