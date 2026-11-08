package me.clama.infra.web;

import org.springframework.http.HttpStatus;

/** Erro esperado de negócio; vira ProblemDetail com codigo e mensagem pastoral. */
public class ErroDeNegocio extends RuntimeException {

    private final HttpStatus status;
    private final String codigo;
    private final String mensagemPastoral;

    public ErroDeNegocio(HttpStatus status, String codigo, String mensagem, String mensagemPastoral) {
        super(mensagem);
        this.status = status;
        this.codigo = codigo;
        this.mensagemPastoral = mensagemPastoral;
    }

    public HttpStatus status() { return status; }

    public String codigo() { return codigo; }

    public String mensagemPastoral() { return mensagemPastoral; }
}
