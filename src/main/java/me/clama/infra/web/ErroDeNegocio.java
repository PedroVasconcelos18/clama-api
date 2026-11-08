package me.clama.infra.web;

import java.util.List;
import org.springframework.http.HttpStatus;

/** Erro esperado de negócio; vira ProblemDetail com codigo e mensagem pastoral. */
public class ErroDeNegocio extends RuntimeException {

    private final HttpStatus status;
    private final String codigo;
    private final String mensagemPastoral;

    private final List<ErroDeCampo> erros;

    public ErroDeNegocio(HttpStatus status, String codigo, String mensagem, String mensagemPastoral) {
        this(status, codigo, mensagem, mensagemPastoral, null);
    }

    public ErroDeNegocio(HttpStatus status, String codigo, String mensagem, String mensagemPastoral,
            List<ErroDeCampo> erros) {
        super(mensagem);
        this.erros = erros;
        this.status = status;
        this.codigo = codigo;
        this.mensagemPastoral = mensagemPastoral;
    }

    public HttpStatus status() { return status; }

    public String codigo() { return codigo; }

    /** Erros por campo (pode ser nulo). */
    public List<ErroDeCampo> erros() { return erros; }

    public String mensagemPastoral() { return mensagemPastoral; }
}
