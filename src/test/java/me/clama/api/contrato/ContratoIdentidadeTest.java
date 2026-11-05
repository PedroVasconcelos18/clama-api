package me.clama.api.contrato;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import me.clama.api.BancoDeTeste;
import me.clama.api.comum.ratelimit.RateLimiter;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.token.ServicoDeTokens;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class ContratoIdentidadeTest {
    static final Path CENARIOS = Path.of("src/test/resources/contrato/identidade");

    @Value("${local.server.port}")
    int porta;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UsuarioRepository usuarios;

    @Autowired
    ServicoDeTokens tokens;

    @Autowired
    RateLimiter limiter;

    static Stream<Named<Path>> cenarios() throws Exception {
        try (Stream<Path> s = Files.list(CENARIOS)) {
            List<Path> lista = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
            assertThat(lista).hasSize(27);
            return lista.stream().map(p -> Named.of(p.getFileName().toString(), p));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cenarios")
    void cenario(Path json) throws Exception {
        new ReplayDeContrato(jdbc, usuarios, tokens, limiter,
                List.of("users_user", "token_blacklist_outstandingtoken", "token_blacklist_blacklistedtoken"))
                .executar(json, URI.create("http://localhost:" + porta));
    }

    @Test
    void normalizacaoIgualAoNormalizarPy() {
        ObjectMapper m = new ObjectMapper();
        var entrada = m.readTree("""
                {"id": 7, "outro": {"id": true}, "lista": [{"id": 3}, 4],
                 "csrf_token": "abc", "ts": "2026-11-05 10:00:00 qualquer coisa", "data": "2026-11-05",
                 "jwt": "eyJa.b-c.", "jwt_nl": "eyJa.b.c\\n", "jwt_cr": "eyJa.b.c\\r", "f": 1.0}""");
        assertThat(ReplayDeContrato.normalizar(entrada, null)).isEqualTo(m.readTree("""
                {"id": "<id>", "outro": {"id": true}, "lista": [{"id": "<id>"}, 4],
                 "csrf_token": "<csrf>", "ts": "<ts>", "data": "2026-11-05",
                 "jwt": "<jwt>", "jwt_nl": "<jwt>", "jwt_cr": "eyJa.b.c\\r", "f": 1.0}"""));
    }
}
