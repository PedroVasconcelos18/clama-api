package me.clama.api.identidade.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import me.clama.api.BancoDeTeste;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.token.ServicoDeTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exemplo da regra que a Task 5 aplica: rota que exige SUPERUSER da 403 para admin comum e 200 para is_superuser. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({BancoDeTeste.class, SuperusuarioTest.Rota.class})
@ActiveProfiles("test")
class SuperusuarioTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class Rota {
        @RestController
        static class Controller {
            @GetMapping("/teste-superusuario/")
            String ok() {
                return "ok";
            }
        }

        @Bean
        @org.springframework.core.annotation.Order(0)
        SecurityFilterChain cadeiaSuperusuario(HttpSecurity http, ServicoDeTokens tokens, UsuarioRepository usuarios,
                CookieCsrfTokenRepository csrf) throws Exception {
            return http.securityMatcher("/teste-superusuario/**").csrf(c -> c.disable())
                    .addFilterBefore(new FiltroJwtCookie(tokens, usuarios, csrf, List.of()),
                            AnonymousAuthenticationFilter.class)
                    .authorizeHttpRequests(a -> a.anyRequest().hasAuthority(FiltroJwtCookie.SUPERUSER))
                    .exceptionHandling(Customizer.withDefaults()).build();
        }
    }

    @Value("${local.server.port}")
    int porta;
    @Autowired JdbcTemplate jdbc;
    @Autowired UsuarioRepository usuarios;
    @Autowired ServicoDeTokens tokens;
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void limpar() {
        BancoDeTeste.exigirContainer(jdbc);
        jdbc.update("delete from token_blacklist_blacklistedtoken");
        jdbc.update("delete from token_blacklist_outstandingtoken");
        jdbc.update("delete from users_user");
    }

    private int status(boolean superuser) throws Exception {
        Usuario u = new Usuario();
        u.setEmail(superuser ? "super@exemplo.com" : "admin@exemplo.com");
        u.setPassword("x");
        u.setClamaAdmin(true);
        u.setSuperuser(superuser);
        u.setActive(true);
        String cookie = "clama_access=" + tokens.emitirPar(usuarios.saveAndFlush(u)).access();
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/teste-superusuario/"))
                .header("Cookie", cookie).build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    void adminComumLeva403() throws Exception {
        assertThat(status(false)).isEqualTo(403);
    }

    @Test
    void superusuarioLeva200() throws Exception {
        assertThat(status(true)).isEqualTo(200);
    }
}
