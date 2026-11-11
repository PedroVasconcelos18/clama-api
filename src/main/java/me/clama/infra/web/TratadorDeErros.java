package me.clama.infra.web;

import io.sentry.Sentry;
import java.util.List;
import me.clama.infra.seguranca.RespostasDeSeguranca;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class TratadorDeErros extends ResponseEntityExceptionHandler {

    static final String PASTORAL_GENERICA = "Algo não saiu como esperado. Tente novamente em instantes.";
    private static final AuthenticationTrustResolver CONFIANCA = new AuthenticationTrustResolverImpl();
    private static final Logger log = LoggerFactory.getLogger(TratadorDeErros.class);

    @ExceptionHandler(ErroDeNegocio.class)
    ResponseEntity<Object> negocio(ErroDeNegocio e, WebRequest req) {
        return resposta(e, e.status(), e.codigo(), e.getMessage(), e.mensagemPastoral(), e.erros(), req);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> acessoNegado(AccessDeniedException e, WebRequest req) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || CONFIANCA.isAnonymous(auth)) { // anonimo precisa entrar, nao "sem permissao"
            return naoAutenticado(new InsufficientAuthenticationException("Autenticação necessária", e), req);
        }
        return resposta(e, HttpStatus.FORBIDDEN, RespostasDeSeguranca.CODIGO_SEM_PERMISSAO,
                "Acesso negado.", RespostasDeSeguranca.PASTORAL_SEM_PERMISSAO, null, req);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> naoAutenticado(AuthenticationException e, WebRequest req) {
        return resposta(e, HttpStatus.UNAUTHORIZED, RespostasDeSeguranca.CODIGO_NAO_AUTENTICADO,
                "Não autenticado.", RespostasDeSeguranca.PASTORAL_NAO_AUTENTICADO, null, req);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> inesperado(Exception e, WebRequest req) {
        return resposta(e, HttpStatus.INTERNAL_SERVER_ERROR, "erro_interno", "Erro interno.", null, null, req);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest req) {
        var erros = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ErroDeCampo(f.getField(), f.getDefaultMessage())).toList();
        return resposta(e, HttpStatus.BAD_REQUEST, "dados_invalidos", "Dados inválidos.", null, erros, req);
    }

    /** Limite do multipart (application.yml) estourado antes de qualquer validacao de dominio. */
    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest req) {
        return resposta(e, HttpStatus.BAD_REQUEST, "arquivo_grande_demais", "Arquivo grande demais.",
                "O arquivo é grande demais. Envie uma versão menor.", null, req);
    }

    /** Multipart malformado (sem boundary, corpo truncado). */
    @ExceptionHandler(MultipartException.class)
    ResponseEntity<Object> multipartInvalido(MultipartException e, WebRequest req) {
        return resposta(e, HttpStatus.BAD_REQUEST, "arquivo_invalido", "Envio de arquivo inválido.", null, null, req);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest req) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            // valor fora do enum: erro no campo, com o texto do sistema antigo (INV:60)
            if (c instanceof tools.jackson.databind.exc.InvalidFormatException f && f.getTargetType() != null
                    && f.getTargetType().isEnum() && !f.getPath().isEmpty()
                    && f.getPath().get(f.getPath().size() - 1).getPropertyName() != null) {
                var campo = f.getPath().get(f.getPath().size() - 1).getPropertyName();
                return resposta(e, HttpStatus.BAD_REQUEST, "dados_invalidos", "Dados inválidos.", null,
                        List.of(new ErroDeCampo(campo, "\"" + f.getValue() + "\" não é uma escolha válida.")), req);
            }
        }
        return resposta(e, HttpStatus.BAD_REQUEST, "corpo_invalido", "Corpo da requisição inválido.", null, null, req);
    }

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest req) {
        return resposta(e, HttpStatus.NOT_FOUND, "nao_encontrado", "Recurso não encontrado.", null, null, req);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException e, HttpHeaders headers,
            HttpStatusCode status, WebRequest req) {
        return resposta(e, HttpStatus.BAD_REQUEST, "requisicao_invalida", "Parâmetro inválido.", null, null, req);
    }

    /** Ponto único: completa codigo/mensagemPastoral nos casos herdados e loga/reporta todo 5xx. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest req) {
        if (body == null && e instanceof ErrorResponse er) {
            body = er.updateAndGetBody(getMessageSource(), LocaleContextHolder.getLocale());
        }
        if (body instanceof ProblemDetail p) {
            if (p.getProperties() == null || !p.getProperties().containsKey("codigo")) {
                p.setProperty("codigo", codigoPadrao(status));
            }
            if (!p.getProperties().containsKey("mensagemPastoral")) {
                p.setProperty("mensagemPastoral", PASTORAL_GENERICA);
            }
        }
        if (e instanceof ErroDeNegocio && status.is5xxServerError()) {
            log.warn("{} {}", status.value(), e.getMessage()); // esperado (ex.: ocupado): sem stack nem Sentry
        } else if (status.is5xxServerError()) {
            log.error("Erro interno", e);
            Sentry.captureException(e);
        }
        return super.handleExceptionInternal(e, body, headers, status, req);
    }

    private static String codigoPadrao(HttpStatusCode status) {
        if (status.is5xxServerError()) return "erro_interno";
        if (status.value() == 405) return "metodo_nao_permitido";
        if (status.value() == 415) return "tipo_de_midia_nao_suportado";
        return "requisicao_invalida";
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
