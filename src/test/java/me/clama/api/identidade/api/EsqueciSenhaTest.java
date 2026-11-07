package me.clama.api.identidade.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import me.clama.api.BancoDeTeste;
import me.clama.api.comum.email.Email;
import me.clama.api.comum.email.EnviadorDeEmail;
import me.clama.api.comum.ratelimit.RateLimiter;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.senha.SenhaDjango;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class EsqueciSenhaTest {
    @Value("${local.server.port}")
    int porta;
    @Autowired UsuarioRepository usuarios;
    @Autowired RateLimiter limiter;
    @MockitoBean EnviadorDeEmail enviador;

    final HttpClient http = HttpClient.newHttpClient();
    final String sufixo = UUID.randomUUID().toString().substring(0, 8).replaceAll("[0-9]", "z");

    @BeforeEach
    void prepara() {
        limiter.limpar();
        reset(enviador);
    }

    private Usuario conta(String email) {
        Usuario u = new Usuario();
        u.setEmail(email);
        u.setPassword("antiga");
        u.setNomeCompleto("Ana Souza");
        return usuarios.saveAndFlush(u);
    }

    private HttpResponse<String> pedir(String email) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + porta
                + "/api/customer/auth/forgot-password/")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"" + email + "\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void forgotGmailCanonicoAcha() throws Exception {
        String local = "ab" + sufixo;
        Usuario u = conta(local + "@gmail.com");
        String dotado = local.charAt(0) + "." + local.substring(1);
        HttpResponse<String> r = pedir(dotado + "+x@gmail.com");
        assertThat(r.statusCode()).isEqualTo(200);
        ArgumentCaptor<Email> e = ArgumentCaptor.forClass(Email.class);
        Mockito.verify(enviador).enviar(e.capture());
        assertThat(e.getValue().para()).isEqualTo(u.getEmail());
        Usuario depois = usuarios.findById(u.getId()).orElseThrow();
        assertThat(depois.isForceChangePassword()).isTrue();
        assertThat(depois.getPassword()).isNotEqualTo("antiga").startsWith("argon2$");
    }

    @Test
    void forgotFalhaDeEmailAindaResponde200() throws Exception {
        Usuario u = conta("falha-" + sufixo + "@x.com");
        doThrow(new IllegalStateException("resend fora")).when(enviador).enviar(any());
        HttpResponse<String> r = pedir(u.getEmail());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("Se este e-mail estiver cadastrado");
        assertThat(usuarios.findById(u.getId()).orElseThrow().isForceChangePassword()).isTrue();
        assertThat(new SenhaDjango().matches("antiga", usuarios.findById(u.getId()).orElseThrow().getPassword()))
                .isFalse();
    }
}
