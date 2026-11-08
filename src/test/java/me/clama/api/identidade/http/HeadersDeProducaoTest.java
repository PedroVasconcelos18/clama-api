package me.clama.api.identidade.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import me.clama.api.BancoDeTeste;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.senha.SenhaDjango;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Paridade com producao (mapa da etapa 9, 0.5 e 2.x): a requisicao chega como no Railway (Host api.clama.me,
 * X-Forwarded-Proto https, Tomcat vendo http) e com a validacao de Host e o cookie Secure ligados, como la.
 */
@SpringBootTest(properties = {"clama.hosts.validar=true", "AUTH_COOKIE_SECURE=true"})
@AutoConfigureMockMvc
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class HeadersDeProducaoTest {
    static final String HSTS = "max-age=60; includeSubDomains; preload";

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuarioRepository usuarios;

    @Value("${server.shutdown}")
    String shutdown;

    @Value("${spring.lifecycle.timeout-per-shutdown-phase}")
    String timeoutDoShutdown;

    @Value("${server.forward-headers-strategy}")
    String forwardHeaders;

    /** GET https://api.clama.me/api/health/ de producao, menos allow/content-language/vary/cache-control (mapa 2.4). */
    @Test
    void healthTrazOsHeadersDeProducao() throws Exception {
        mvc.perform(get("/api/health/").header("Host", "api.clama.me").header("X-Forwarded-Proto", "https"))
                .andExpect(status().isOk())
                .andExpect(header().string("Strict-Transport-Security", HSTS))
                .andExpect(header().string("Cross-Origin-Opener-Policy", "same-origin"))
                .andExpect(header().string("Referrer-Policy", "same-origin"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    void semXForwardedProtoNaoSaiHsts() throws Exception {
        mvc.perform(get("/api/health/").header("Host", "api.clama.me"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Strict-Transport-Security"))
                .andExpect(header().string("Cross-Origin-Opener-Policy", "same-origin"));
    }

    /** SECURE_PROXY_SSL_HEADER: so o 1o valor da lista vale. */
    @Test
    void soOPrimeiroValorDoXForwardedProtoConta() throws Exception {
        mvc.perform(get("/api/health/").header("Host", "api.clama.me").header("X-Forwarded-Proto", "https, http"))
                .andExpect(header().string("Strict-Transport-Security", HSTS));
        mvc.perform(get("/api/health/").header("Host", "api.clama.me").header("X-Forwarded-Proto", "http, https"))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }

    @Test
    void hostForaDaListaDa400ComAPaginaDoDjango() throws Exception {
        mvc.perform(get("/api/health/").header("Host", "evil.com"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType("text/html;charset=utf-8"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<h1>Bad Request (400)</h1>")));
        mvc.perform(get("/api/health/").header("Host", "api.clama.me.evil.com")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/health/").header("Host", "api.clama.me\\@evil.com")).andExpect(status().isBadRequest());
    }

    @Test
    void hostDaListaPassaComPortaEMaiusculas() throws Exception {
        for (String host : List.of("api.clama.me", "API.Clama.me:443", "clama.me", "www.clama.me")) {
            mvc.perform(get("/api/health/").header("Host", host)).andExpect(status().isOk());
        }
    }

    @Test
    void healthcheckDoRailwayPassa() throws Exception {
        mvc.perform(get("/api/health/").header("Host", "healthcheck.railway.app")).andExpect(status().isOk());
    }

    @Test
    void curingaEPortaNoDjangoAllowedHosts() {
        FiltroHostPermitido f = new FiltroHostPermitido(true, ".clama.me, exemplo.com ,");
        assertThat(f.permitido("clama.me")).isTrue();
        assertThat(f.permitido("a.b.clama.me")).isTrue();
        assertThat(f.permitido("exemplo.com:8080")).isTrue();
        assertThat(f.permitido("xclama.me")).isFalse();
        assertThat(f.permitido("sub.exemplo.com")).isFalse();
        assertThat(f.permitido(null)).isFalse();
        assertThat(new FiltroHostPermitido(true, "*").permitido("qualquer.coisa")).isTrue();
        assertThat(new FiltroHostPermitido(true, "").permitido("clama.me")).isFalse();
    }

    @Test
    void corpoGrandeNoWebhookDoMercadoPagoDa400AntesDoHmac() throws Exception {
        mvc.perform(post("/api/webhooks/mercadopago/").header("Host", "api.clama.me")
                        .header("X-Forwarded-Proto", "https").contentType(MediaType.APPLICATION_JSON)
                        .content(new byte[3 * 1024 * 1024]))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Bad Request (400)")));
        // abaixo do teto segue para o HMAC (sem assinatura: 401)
        mvc.perform(post("/api/webhooks/mercadopago/").header("Host", "api.clama.me")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void actuatorNaoEExposto() throws Exception {
        for (String caminho : List.of("/actuator", "/actuator/health", "/actuator/env", "/actuator/info")) {
            mvc.perform(get(caminho).header("Host", "api.clama.me")).andExpect(status().isNotFound());
        }
    }

    /** Os tres cookies de auth: Secure; HttpOnly; SameSite=Lax; Path=/api (mapa 2.7). */
    @Test
    void cookiesDeAuthSaemSecure() throws Exception {
        String email = "ana-" + System.nanoTime() + "@x.com";
        Usuario u = new Usuario();
        u.setEmail(email);
        u.setPassword(new SenhaDjango().encode("Clama-forte-2026"));
        usuarios.saveAndFlush(u);
        MvcResult r = mvc.perform(post("/api/customer/auth/login/").header("Host", "api.clama.me")
                        .header("X-Forwarded-Proto", "https").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Clama-forte-2026\"}"))
                .andExpect(status().isOk()).andReturn();
        List<String> cookies = new java.util.ArrayList<>(r.getResponse().getHeaders("Set-Cookie"));
        cookies.addAll(mvc.perform(get("/api/csrf/").header("Host", "api.clama.me")).andReturn().getResponse()
                .getHeaders("Set-Cookie"));
        assertThat(cookies).hasSize(3);
        for (String nome : List.of("clama_access", "clama_refresh", "csrftoken")) {
            String c = cookies.stream().filter(x -> x.startsWith(nome + "=")).findFirst().orElseThrow();
            assertThat(c).contains("Secure").contains("HttpOnly").contains("Path=/api").doesNotContain("Domain");
            // SameSite=Lax: o MockHttpServletResponse nao escreve o atributo do csrftoken; SegurancaTest confere no Tomcat
            if (!nome.equals("csrftoken")) {
                assertThat(c).contains("SameSite=Lax");
            }
        }
    }

    @Test
    void shutdownGracefulEProxyHeadersDesligado() {
        assertThat(shutdown).isEqualTo("graceful");
        assertThat(timeoutDoShutdown).isEqualTo("15s");
        assertThat(forwardHeaders).isEqualTo("none");
    }

    /**
     * Sem o InMemoryUserDetailsManager do Boot, que loga "Using generated security password" no boot (ensaio da
     * Etapa 9): a autenticacao e so por JWT em cookie, nenhum usuario por nome.
     */
    @Test
    void semUsuarioEmMemoria(@Autowired UserDetailsService usuariosPorNome) {
        assertThatThrownBy(() -> usuariosPorNome.loadUserByUsername("user"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
