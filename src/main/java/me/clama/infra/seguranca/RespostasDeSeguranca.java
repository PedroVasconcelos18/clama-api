package me.clama.infra.seguranca;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 401 e 403 em ProblemDetail, para as barreiras do filtro; o TratadorDeErros reusa as constantes. */
@Component
public class RespostasDeSeguranca implements AuthenticationEntryPoint, AccessDeniedHandler {

	public static final String CODIGO_NAO_AUTENTICADO = "nao_autenticado";
	public static final String PASTORAL_NAO_AUTENTICADO = "Entre na sua conta para continuar.";
	public static final String CODIGO_SEM_PERMISSAO = "sem_permissao";
	public static final String PASTORAL_SEM_PERMISSAO = "Você não tem acesso a esta área.";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Override
	public void commence(HttpServletRequest req, HttpServletResponse res, AuthenticationException e)
			throws IOException {
		escrever(res, HttpStatus.UNAUTHORIZED, CODIGO_NAO_AUTENTICADO, PASTORAL_NAO_AUTENTICADO);
	}

	@Override
	public void handle(HttpServletRequest req, HttpServletResponse res, AccessDeniedException e)
			throws IOException {
		escrever(res, HttpStatus.FORBIDDEN, CODIGO_SEM_PERMISSAO, PASTORAL_SEM_PERMISSAO);
	}

	private static void escrever(HttpServletResponse res, HttpStatus status, String codigo, String pastoral)
			throws IOException {
		res.setStatus(status.value());
		res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		res.setCharacterEncoding(StandardCharsets.UTF_8.name());
		JSON.writeValue(res.getOutputStream(), Map.of("type", "about:blank", "title", status.getReasonPhrase(),
				"status", status.value(), "codigo", codigo, "mensagemPastoral", pastoral));
	}
}
