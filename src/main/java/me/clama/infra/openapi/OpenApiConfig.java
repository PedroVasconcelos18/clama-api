package me.clama.infra.openapi;

import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import me.clama.infra.seguranca.RespostasDeSeguranca;
import me.clama.infra.seguranca.SegurancaConfig;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** OpenAPI so quando springdoc.api-docs.enabled=true (local e testes); em producao a cadeia padrao nega. */
@Configuration
@ConditionalOnProperty("springdoc.api-docs.enabled")
class OpenApiConfig {

	@Bean
	@Order(5)
	SecurityFilterChain cadeiaDoOpenApi(HttpSecurity http, RespostasDeSeguranca respostas) throws Exception {
		return SegurancaConfig.aplicarPadrao(http, respostas)
				.securityMatcher("/v3/api-docs", "/v3/api-docs/**")
				.authorizeHttpRequests(a -> a.anyRequest().permitAll())
				.build();
	}

	/** O erro que o TratadorDeErros devolve: ProblemDetail (RFC 9457) + codigo, mensagemPastoral e erros. */
	@Bean
	OpenApiCustomizer schemaDeErro() {
		return api -> {
			Schema<?> erroDeCampo = new ObjectSchema().addProperty("campo", new StringSchema())
					.addProperty("mensagem", new StringSchema());
			Schema<?> problema = new ObjectSchema()
					.addProperty("type", new StringSchema().format("uri"))
					.addProperty("title", new StringSchema())
					.addProperty("status", new IntegerSchema().format("int32"))
					.addProperty("detail", new StringSchema())
					.addProperty("instance", new StringSchema().format("uri"))
					.addProperty("codigo", new StringSchema().description("identificador estavel do erro"))
					.addProperty("mensagemPastoral", new StringSchema().description("texto para mostrar a pessoa"))
					.addProperty("erros", new ArraySchema().items(new Schema<>().$ref("#/components/schemas/ErroDeCampo"))
							.description("erros por campo, quando e validacao"));
			api.getComponents().addSchemas("ErroDeCampo", erroDeCampo).addSchemas("ProblemDetail", problema);
		};
	}
}
