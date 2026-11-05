package me.clama.api.identidade.http;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import me.clama.api.comum.erro.PastoralException;
import me.clama.api.comum.erro.PastoralExceptionHandler;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.token.ServicoDeTokens;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
public class SegurancaConfig {
    /** IsAuthenticated no Django. As views chegam na Task 8. */
    static final String[] ROTAS_AUTENTICADAS = {"/api/customer/me/", "/api/customer/auth/logout/",
        "/api/customer/auth/change-password/"};
    static final String SEM_PERMISSAO = "Você não tem permissão para executar essa ação.";

    /** CSRF_COOKIE_PATH=/api, CSRF_COOKIE_HTTPONLY=True, SameSite e idade padrao do Django (1 ano). */
    @Bean
    CookieCsrfTokenRepository csrfTokenRepository(@Value("${AUTH_COOKIE_SECURE:true}") boolean secure) {
        CookieCsrfTokenRepository r = new CookieCsrfTokenRepository();
        r.setCookieName("csrftoken");
        r.setHeaderName("X-CSRFToken");
        r.setCookieCustomizer(c -> c.path("/api").httpOnly(true).secure(secure).sameSite("Lax").maxAge(31449600));
        return r;
    }

    @Bean
    SecurityFilterChain filtros(HttpSecurity http, ServicoDeTokens tokens, UsuarioRepository usuarios,
            CookieCsrfTokenRepository csrf,
            @Value("${DJANGO_CSRF_TRUSTED_ORIGINS:https://clama.me,https://www.clama.me}") String confiaveis,
            @Value("${DJANGO_CORS_ALLOWED_ORIGINS:}") String cors) throws Exception {
        // O CSRF fica no FiltroJwtCookie (como no Django, so pra quem autenticou por cookie), nao no CsrfFilter.
        return http.csrf(c -> c.disable())
                .cors(c -> c.configurationSource(cors(lista(cors))))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable())
                .logout(c -> c.disable())
                .addFilterBefore(new FiltroJwtCookie(tokens, usuarios, csrf, lista(confiaveis)),
                        AnonymousAuthenticationFilter.class)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, resp, ex) ->
                                PastoralExceptionHandler.escrever(resp, PastoralException.naoAutenticado()))
                        .accessDeniedHandler((req, resp, ex) ->
                                PastoralExceptionHandler.escrever(resp, 403, Map.of("detail", SEM_PERMISSAO))))
                .authorizeHttpRequests(a -> a.requestMatchers(ROTAS_AUTENTICADAS).authenticated()
                        .anyRequest().permitAll())
                .build();
    }

    /**
     * django-cors-headers com CORS_ALLOW_CREDENTIALS. Origem fora da lista nao e rejeitada, so fica sem os
     * headers: devolver null faz o CorsFilter do Spring ignorar o pedido em vez de responder 403.
     */
    private static CorsConfigurationSource cors(List<String> origens) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(origens);
        c.setAllowCredentials(true);
        c.setAllowedMethods(List.of("DELETE", "GET", "OPTIONS", "PATCH", "POST", "PUT"));
        c.setAllowedHeaders(List.of("accept", "authorization", "content-type", "user-agent", "x-csrftoken",
                "x-requested-with"));
        c.setMaxAge(86400L);
        return req -> origens.contains(req.getHeader("Origin")) ? c : null;
    }

    private static List<String> lista(String v) {
        return Arrays.stream(v.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
