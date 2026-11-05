package me.clama.api.comum.email.modelo;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import me.clama.api.comum.email.Email;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Compara byte a byte com o que o email_sender.py do Django gerou (gravador: contrato/test_gravar_emails.py). */
class ContratoEmailsTest {
    static final Path CENARIOS = Path.of("src/test/resources/contrato/emails");
    // O gravador fixa FRONTEND_BASE_URL em "https://clama.me/" (com barra, pra provar o rstrip).
    final ModelosDeEmail modelos = new ModelosDeEmail("https://clama.me/", "");

    static Stream<Named<Path>> cenarios() throws Exception {
        try (Stream<Path> s = Files.list(CENARIOS)) {
            List<Path> lista = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
            assertThat(lista).hasSize(9);
            return lista.stream().map(p -> Named.of(p.getFileName().toString(), p));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cenarios")
    void identicoAoDjango(Path json) throws Exception {
        JsonNode c = new ObjectMapper().readTree(Files.readString(json));
        JsonNode e = c.get("entrada");
        Email esperado = new Email(c.get("para").get(0).asString(), c.get("assunto").asString(),
                c.get("texto").asString(), c.get("html").asString());
        assertThat(c.get("reply_to").get(0).asString()).isEqualTo("contato@clama.me");

        Email obtido = switch (e.get("tipo").asString()) {
            case "oracao" -> modelos.oracao(s(e, "para"), s(e, "nome"), s(e, "oracao"));
            case "oracao_freemium" -> modelos.oracaoFreemium(s(e, "para"), s(e, "nome"), s(e, "oracao"),
                    s(e, "login_email"), s(e, "senha_temporaria"));
            case "confirmacao_freemium" -> modelos.confirmacaoFreemium(s(e, "para"), s(e, "nome"),
                    s(e, "link_confirmacao"));
            case "recuperacao_senha" -> modelos.recuperacaoSenha(s(e, "para"), s(e, "primeiro_nome"),
                    s(e, "senha_temporaria"));
            case "boas_vindas_doador" -> modelos.boasVindasDoador(s(e, "para"), s(e, "nome_completo"),
                    s(e, "senha_temporaria"), s(e, "login_url"));
            default -> throw new IllegalStateException(e.get("tipo").asString());
        };

        assertThat(obtido.para()).isEqualTo(esperado.para());
        assertThat(obtido.assunto()).isEqualTo(esperado.assunto());
        assertThat(obtido.texto()).isEqualTo(esperado.texto());
        assertThat(obtido.html()).isEqualTo(esperado.html());
    }

    static String s(JsonNode n, String campo) {
        return n.get(campo).asString();
    }
}
