package me.clama.api.comum.erro;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.clama.api.comum.api.CamposDrf;
import me.clama.api.comum.api.FormularioDjango;
import me.clama.api.comum.api.ParametroInvalido;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.databind.ObjectMapper;

/** pastoral_exception_handler do Django. */
@RestControllerAdvice
public class PastoralExceptionHandler {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String JSON_INVALIDO = "JSON parse error - Invalid JSON";

    @ExceptionHandler(PastoralException.class)
    ResponseEntity<Map<String, Object>> pastoral(PastoralException e) {
        return ResponseEntity.status(e.status()).body(e.corpo());
    }

    /** Erro de campo no formato do DRF: {"campo":["msg"]}. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, List<String>>> campos(MethodArgumentNotValidException e) {
        Map<String, List<String>> corpo = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(f -> corpo.computeIfAbsent(f.getField(), k -> new ArrayList<>()).add(f.getDefaultMessage()));
        return ResponseEntity.badRequest().body(corpo);
    }

    @ExceptionHandler(ParametroInvalido.class)
    ResponseEntity<Map<String, String>> parametroInvalido(ParametroInvalido e) {
        return ResponseEntity.badRequest().body(Map.of("detail", e.getMessage()));
    }

    @ExceptionHandler(NaoEncontrado.class)
    ResponseEntity<Map<String, String>> naoEncontrado(NaoEncontrado e) {
        return ResponseEntity.status(404).body(Map.of("detail", e.getMessage()));
    }

    /** URL sem rota (ex. uuid mal formado em /api/pedidos/{id}/): a pagina 404 do Django, que e HTML. */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<String> semRota() {
        return ResponseEntity.status(404).contentType(org.springframework.http.MediaType.TEXT_HTML)
                .body("\n<!doctype html>\n<html lang=\"en\">\n<head>\n  <title>Not Found</title>\n</head>\n<body>\n"
                        + "  <h1>Not Found</h1><p>The requested resource was not found on this server.</p>\n"
                        + "</body>\n</html>\n");
    }

    @ExceptionHandler(CamposDrf.Invalidos.class)
    ResponseEntity<Map<String, List<String>>> campos(CamposDrf.Invalidos e) {
        return ResponseEntity.badRequest().body(e.erros());
    }

    /**
     * ParseError do DRF ("JSON parse error - " + erro do json do Python). Texto fixo depois do prefixo: a mensagem do
     * Jackson traz nome de classe e assinatura Java. Corpo vazio nao chega aqui (vira {} no CamposDrf).
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> jsonInvalido(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of("detail", JSON_INVALIDO));
    }

    @ExceptionHandler(FormularioDjango.TipoNaoSuportado.class)
    ResponseEntity<Map<String, String>> tipoNaoSuportado(FormularioDjango.TipoNaoSuportado e) {
        return ResponseEntity.status(415).body(Map.of("detail", e.getMessage()));
    }

    /** MethodNotAllowed do DRF. So rota conhecida chega aqui: URL desconhecida continua 404. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String, String>> metodoNaoPermitido(HttpRequestMethodNotSupportedException e) {
        var r = ResponseEntity.status(405);
        if (e.getSupportedHttpMethods() != null) {
            r.allow(e.getSupportedHttpMethods().toArray(HttpMethod[]::new));
        }
        return r.body(Map.of("detail", "Método \"" + e.getMethod() + "\" não é permitido."));
    }

    /** Para quem responde fora do Spring MVC (filtros de seguranca). */
    public static void escrever(HttpServletResponse resp, int status, Object corpo) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        JSON.writeValue(resp.getOutputStream(), corpo);
    }

    public static void escrever(HttpServletResponse resp, PastoralException e) throws IOException {
        escrever(resp, e.status(), e.corpo());
    }
}
