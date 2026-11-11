package me.clama.blog.web.widget;

import io.sentry.Sentry;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import me.clama.infra.web.ErroDeNegocio;

/**
 * Erros do widget no formato plano {@code {code, pastoral_message}} (mapa §1.9, formato 3): e o que o widget le em todos os
 * casos. So vale para o {@link WidgetController}; o resto da API segue em ProblemDetail. Nada aqui loga mensagem de
 * excecao com dado do usuario.
 */
@RestControllerAdvice(assignableTypes = WidgetController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class ErrosDoWidget {

	static final String PASTORAL_GENERICA = "Algo não saiu como esperado. Tente novamente em instantes.";
	private static final Logger log = LoggerFactory.getLogger(ErrosDoWidget.class);

	@ExceptionHandler(ErroDeNegocio.class)
	ResponseEntity<Map<String, String>> negocio(ErroDeNegocio e) {
		if (e.status().is5xxServerError()) log.warn("{} {}", e.status().value(), e.codigo()); // esperado (WordPress fora): sem stack
		return plano(e.status(), e.codigo(), e.mensagemPastoral());
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<Map<String, String>> corpoIlegivel() {
		return plano(HttpStatus.BAD_REQUEST, "corpo_invalido", PASTORAL_GENERICA);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<Map<String, String>> inesperado(Exception e) {
		// excecoes do MVC com status proprio (406, 405, 415...): o status delas, sem Sentry
		if (e instanceof ErrorResponse er) return plano(er.getStatusCode(), "requisicao_invalida", PASTORAL_GENERICA);
		log.error("Erro interno no widget: {}", e.getClass().getName()); // so o tipo: a mensagem pode carregar dado do usuario
		Sentry.captureException(e);
		return plano(HttpStatus.INTERNAL_SERVER_ERROR, "erro_interno", PASTORAL_GENERICA);
	}

	private static ResponseEntity<Map<String, String>> plano(HttpStatusCode status, String codigo, String pastoral) {
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Map.of("code", codigo, "pastoral_message", pastoral != null ? pastoral : PASTORAL_GENERICA));
	}
}
