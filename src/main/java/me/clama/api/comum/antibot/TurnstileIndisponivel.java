package me.clama.api.comum.antibot;

/** Erro de rede, de HTTP (4xx, ou 5xx apos os retries) ou corpo ilegivel: o Django responde 400 captcha_invalido. */
public class TurnstileIndisponivel extends RuntimeException {
    public TurnstileIndisponivel(Throwable causa) {
        super("Turnstile indisponivel: " + causa.getMessage(), causa);
    }
}
