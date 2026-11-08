package me.clama.infra.web;

import io.sentry.Sentry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class TratadorDeErros extends ResponseEntityExceptionHandler {

    static final String PASTORAL_GENERICA = "Algo não saiu como esperado. Tente novamente em instantes.";
    private static final Logger log = LoggerFactory.getLogger(TratadorDeErros.class);

    @ExceptionHandler(ErroDeNegocio.class)
    ResponseEntity<Object> negocio(ErroDeNegocio e, WebRequest req) {
        return resposta(e, e.status(), e.codigo(), e.getMessage(), e.mensagemPastoral(), null, req);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> inesperado(Exception e, WebRequest req) {
        log.error("Erro interno não tratado", e);
        Sentry.captureException(e);
        return resposta(e, HttpStatus.INTERNAL_SERVER_ERROR, "erro_interno", "Erro interno.", null, null, req);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest req) {
        var erros = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ErroDeCampo(f.getField(), f.getDefaultMessage())).toList();
        return resposta(e, HttpStatus.BAD_REQUEST, "dados_invalidos", "Dados inválidos.", null, erros, req);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest req) {
        return resposta(e, HttpStatus.BAD_REQUEST, "corpo_invalido", "Corpo da requisição inválido.", null, null, req);
    }

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest req) {
        return resposta(e, HttpStatus.NOT_FOUND, "nao_encontrado", "Recurso não encontrado.", null, null, req);
    }

    private ResponseEntity<Object> resposta(Exception e, HttpStatus status, String codigo, String detail,
            String pastoral, List<ErroDeCampo> erros, WebRequest req) {
        var p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setProperty("codigo", codigo);
        p.setProperty("mensagemPastoral", pastoral != null ? pastoral : PASTORAL_GENERICA);
        if (erros != null) p.setProperty("erros", erros);
        return handleExceptionInternal(e, p, new HttpHeaders(), status, req);
    }
}
