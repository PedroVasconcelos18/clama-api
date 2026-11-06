package me.clama.api.identidade.http;

import jakarta.servlet.DispatcherType;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import me.clama.api.comum.erro.PastoralException;
import me.clama.api.comum.erro.PastoralExceptionHandler;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.token.ServicoDeTokens;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;
import org.springframework.web.util.ServletRequestPathUtils;

@Configuration
public class SegurancaConfig {
    static final String SEM_PERMISSAO = "Você não tem permissão para executar essa ação.";
    /** MSG_NO_PERMISSION do permission_denied do AdminAPIView/AdminGenericAPIView. */
    static final String SO_ADMIN = "Esse espaço é só para admins do Clama.";
    /**
     * permission_classes = [IsAuthenticated, IsCustomerPasswordCurrent] (clama/core/permissions.py): anonimo 401,
     * senha temporaria 403 pastoral. Roda no filtro, antes do throttle, como o check_permissions do DRF.
     */
    static final String[] SENHA_EM_DIA = {"/api/pedidos/", "/api/pedidos/gratuito/"};

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
            CookieCsrfTokenRepository csrf, ObjectProvider<HandlerMapping> mapeamentos,
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
                        .accessDeniedHandler((req, resp, ex) -> {
                            if (ex instanceof SenhaTemporaria) {
                                PastoralExceptionHandler.escrever(resp, PastoralException.senhaTemporaria());
                            } else {
                                PastoralExceptionHandler.escrever(resp, 403, Map.of("detail",
                                        req.getRequestURI().startsWith("/api/admin/") ? SO_ADMIN : SEM_PERMISSAO));
                            }
                        }))
                // nega por padrao: rota nova sob /api/admin/ ja nasce IsClamaAdmin, sem lista pra manter
                .authorizeHttpRequests(a -> a
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll() // /error de pedido ja autorizado
                        .requestMatchers(FiltroJwtCookie.PUBLICAS, FiltroJwtCookie.ABERTAS).permitAll()
                        .requestMatchers(semRota(mapeamentos)).permitAll()
                        .requestMatchers("/api/admin/**").hasAuthority(FiltroJwtCookie.ADMIN)
                        .requestMatchers(SENHA_EM_DIA).access(SegurancaConfig::senhaEmDia)
                        .requestMatchers("/api/customer/**").authenticated()
                        .anyRequest().denyAll())
                .build();
    }

    /** CustomerPasswordChangeRequiredError: vira o 403 pastoral no accessDeniedHandler. */
    static final class SenhaTemporaria extends AccessDeniedException {
        SenhaTemporaria() {
            super("force_change_password");
        }
    }

    private static AuthorizationResult senhaEmDia(Supplier<? extends Authentication> auth,
            RequestAuthorizationContext ctx) {
        Authentication a = auth.get();
        if (a == null || !(a.getPrincipal() instanceof Usuario u)) {
            return new AuthorizationDecision(false); // anonimo: o ExceptionTranslationFilter responde 401
        }
        if (u.isForceChangePassword()) {
            throw new SenhaTemporaria();
        }
        return new AuthorizationDecision(true);
    }

    /**
     * URL que nenhum handler atende (fora o de arquivos estaticos) segue pro 404 do dispatcher, como o Django
     * responde URL desconhecida. Na duvida (excecao no lookup, ex. metodo errado numa rota que existe) nao libera.
     */
    private static RequestMatcher semRota(ObjectProvider<HandlerMapping> mapeamentos) {
        return req -> {
            boolean parseado = ServletRequestPathUtils.hasParsedRequestPath(req);
            if (!parseado) {
                ServletRequestPathUtils.parseAndCache(req);
            }
            try {
                for (HandlerMapping m : mapeamentos.orderedStream().toList()) {
                    HandlerExecutionChain h = m.getHandler(req);
                    if (h != null && !(h.getHandler() instanceof ResourceHttpRequestHandler)) {
                        return false;
                    }
                }
                return true;
            } catch (Exception e) {
                return false;
            } finally {
                if (!parseado) {
                    ServletRequestPathUtils.clearParsedRequestPath(req);
                }
            }
        };
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
