package me.clama.api.comum.erro;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.clama.api.comum.api.CamposDrf;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
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
