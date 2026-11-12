package me.clama.infra.seguranca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import me.clama.BancoDeTeste;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@AutoConfigureMockMvc
@Import({ BancoDeTeste.class, SegurancaTest.CadeiaDeModuloParaTeste.class })
@ExtendWith(OutputCaptureExtension.class)
class SegurancaTest {

	/** Convencao das ondas seguintes: cadeia do modulo = padrao comum + securityMatcher + @Order + regras. */
	@TestConfiguration(proxyBeanMethods = false)
	static class CadeiaDeModuloParaTeste {
		@Bean
		@Order(1)
		SecurityFilterChain cadeiaDoModulo(HttpSecurity http, RespostasDeSeguranca respostas) throws Exception {
			return SegurancaConfig.aplicarPadrao(http, respostas)
					.securityMatcher("/api/modulo-teste/**")
					.authorizeHttpRequests(a -> a.anyRequest().permitAll())
					.build();
		}
	}

	@Autowired
	MockMvc mvc;

	@LocalServerPort
	int porta;

	@Test
	void hostForaDaListaLeva421MasOHealthcheckPassaComQualquerHost() throws Exception {
		mvc.perform(get("/api/admin/x").header("Host", "x.up.railway.app")).andExpect(status().isMisdirectedRequest());
		mvc.perform(get("/actuator/health/readiness").header("Host", "healthcheck.railway.app")).andExpect(status().isOk());
	}

	@Test
	void rejeicaoDoFirewallDa400Nunca401() throws Exception {
		// MockMvc nao passa pelo container: so um cliente real exercita o dispatch de ERROR
		var res = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/api//x")).build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(res.statusCode()).isEqualTo(400);
	}

	@Test
	void anonimo401NaoCriaSessao() throws Exception {
		var res = mvc.perform(get("/api/admin/x")).andExpect(status().isUnauthorized()).andReturn().getResponse();
		assertThat(res.getHeaders("Set-Cookie")).noneMatch(c -> c.contains("SESSION")); // SESSION e JSESSIONID
	}

	@Test
	@WithMockUser(roles = "ADMIN")
	void postSemTokenDaCsrfInvalido() throws Exception {
		mvc.perform(post("/api/admin/teste"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("csrf_invalido"))
				.andExpect(jsonPath("$.mensagemPastoral").value(
						"Não conseguimos confirmar que este pedido veio de você. Recarregue a página e tente de novo."));
	}

	@Autowired
	ApplicationContext contexto;

	@Test
	void cadeiaDeModuloLiberaSuaRotaComCookieCsrfENaoESombreada() throws Exception {
		mvc.perform(get("/api/modulo-teste/x"))
				.andExpect(status().isOk())
				.andExpect(cookie().exists("XSRF-TOKEN"))
				.andExpect(header().string("X-Frame-Options", "DENY"));
	}

	@Test
	void cadeiaDeModuloExigeCsrfNoPost() throws Exception {
		mvc.perform(post("/api/modulo-teste/x")).andExpect(status().isForbidden());
	}

	@Test
	void acessoNegadoDeAnonimoDa401() throws Exception {
		mvc.perform(get("/api/modulo-teste/negado"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.codigo").value("nao_autenticado"))
				.andExpect(jsonPath("$.mensagemPastoral").value("Entre na sua conta para continuar."));
	}

	@Test
	@WithMockUser(roles = "CLIENTE")
	void acessoNegadoDeAutenticadoContinuaDando403() throws Exception {
		mvc.perform(get("/api/modulo-teste/negado"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("sem_permissao"));
	}

	@Test
	void credenciaisInvalidasDentroDoControllerDao401() throws Exception {
		mvc.perform(get("/api/modulo-teste/credenciais"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.codigo").value("nao_autenticado"));
	}

	@Test
	void adminSemLoginDa401EmJson() throws Exception {
		mvc.perform(get("/api/admin/teste"))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.codigo").value("nao_autenticado"))
				.andExpect(jsonPath("$.mensagemPastoral").value("Entre na sua conta para continuar."))
				.andExpect(header().doesNotExist("Location"))
				.andExpect(header().doesNotExist("WWW-Authenticate"));
	}

	@Test
	@WithMockUser(roles = "CLIENTE")
	void adminComUsuarioSemPapelDa403() throws Exception {
		mvc.perform(get("/api/admin/teste"))
				.andExpect(status().isForbidden())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.codigo").value("sem_permissao"))
				.andExpect(jsonPath("$.mensagemPastoral").value("Você não tem acesso a esta área."));
	}

	@Test
	@WithMockUser(roles = "ADMIN")
	void adminComPapelPassa() throws Exception {
		mvc.perform(get("/api/admin/teste")).andExpect(status().isOk());
	}

	@Test
	void rotaSemRegraNegada() throws Exception {
		var status = mvc.perform(get("/api/publico/teste")).andReturn().getResponse().getStatus();
		assertThat(status).isIn(401, 403);
	}

	@Test
	@WithMockUser(roles = "ADMIN")
	void postSemCsrfDa403() throws Exception {
		mvc.perform(post("/api/admin/teste")).andExpect(status().isForbidden());
	}

	@Test
	@WithMockUser(roles = "ADMIN")
	void postComTokenCsrfPassa() throws Exception {
		// Fluxo real da SPA: le o cookie da primeira resposta e devolve no header.
		// (O post-processor csrf() nao serve: troca o repositorio do filtro e vaza para os outros testes.)
		var cookie = mvc.perform(get("/actuator/health")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		mvc.perform(post("/api/admin/teste").cookie(cookie).header("X-XSRF-TOKEN", cookie.getValue()))
				.andExpect(status().isOk());
	}

	@Test
	void cookieXsrfVemNaPrimeiraRespostaELegivelPeloJs() throws Exception {
		mvc.perform(get("/actuator/health"))
				.andExpect(cookie().exists("XSRF-TOKEN"))
				.andExpect(cookie().httpOnly("XSRF-TOKEN", false));
	}

	@Test
	@WithMockUser(roles = "ADMIN")
	void excecaoDeAcessoNegadoDentroDoControllerDa403Nao500() throws Exception {
		mvc.perform(get("/api/admin/teste/negado"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("sem_permissao"))
				.andExpect(jsonPath("$.mensagemPastoral").value("Você não tem acesso a esta área."));
	}

	@Test
	void headersDeSeguranca() throws Exception {
		mvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(header().string("X-Frame-Options", "DENY"))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(header().string("Referrer-Policy", "same-origin"))
				.andExpect(header().string("Cross-Origin-Opener-Policy", "same-origin"))
				.andExpect(header().doesNotExist("Strict-Transport-Security"));
	}

	@Test
	void hstsSoSobHttps() throws Exception {
		mvc.perform(get("/actuator/health").secure(true))
				.andExpect(header().string("Strict-Transport-Security", "max-age=31536000 ; includeSubDomains"));
	}

	@Test
	void semSenhaGeradaPeloBoot(CapturedOutput saida) {
		assertThat(saida).doesNotContain("Using generated security password");
		// o log so aparece se este teste subir o contexto; a ausencia do bean prova o mesmo em qualquer ordem
		assertThat(contexto.getBeanNamesForType(UserDetailsService.class)).isEmpty();
	}
}
