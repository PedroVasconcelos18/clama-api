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

/** Cenarios gravados por clama-backend/contrato/test_gravar_catalogo.py. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class ContratoCatalogoTest {
    static final Path CENARIOS = Path.of("src/test/resources/contrato/catalogo");
    /** Grupos ligados, por prefixo do arquivo. Cada task move o seu prefixo de PENDENTES para LIGADOS. */
    static final List<String> LIGADOS = List.of("admin_", "planos_", "instituicoes_", "prompts_");
    /** Gravados mas ainda sem implementacao. Fica vazio na Task 5. */
    static final List<String> PENDENTES = List.of("documentos_");
    static final List<String> TABELAS = List.of("users_user", "token_blacklist_outstandingtoken",
            "token_blacklist_blacklistedtoken", "plans_plan", "instituicoes_instituicao", "prompts_prompttemplate",
            "documents_documentocontexto");

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
            return lista.stream().filter(p -> grupo(p, LIGADOS)).map(p -> Named.of(p.getFileName().toString(), p));
        }
    }

    static boolean grupo(Path p, List<String> prefixos) {
        return prefixos.stream().anyMatch(p.getFileName().toString()::startsWith);
    }

    /** Cenario gravado nao pode sumir sem aviso: ou esta ligado, ou esta pendente (em um so). */
    @Test
    void todoCenarioLigadoOuPendente() throws Exception {
        try (Stream<Path> s = Files.list(CENARIOS)) {
            assertThat(s.filter(p -> p.toString().endsWith(".json")).toList()).isNotEmpty()
                    .allSatisfy(p -> assertThat(grupo(p, LIGADOS)).as("%s", p).isNotEqualTo(grupo(p, PENDENTES)));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cenarios")
    void cenario(Path json) throws Exception {
        new ReplayDeContrato(jdbc, usuarios, tokens, limiter, TABELAS)
                .executar(json, URI.create("http://localhost:" + porta));
    }
}
