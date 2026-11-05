package me.clama.api.comum.cripto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import me.clama.api.identidade.token.Jwt;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Sem default no main: segredo faltando ou em branco derruba o startup com o nome da variavel. */
class SegredosObrigatoriosTest {
    // a chave do application-test.yml (CampoCriptografado e global; a mesma chave nao atrapalha outros testes)
    static final String[] TODOS = {"FIELD_ENCRYPTION_KEY=-dPyxte5HDDyxBQExFzkphbnXt2xhs9FWlMLPFQpeuw=",
        "FREEMIUM_HASH_SECRET=s", "DJANGO_SECRET_KEY=k"};

    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CriptoConfig.class, Jwt.class).withPropertyValues(TODOS);

    @Test
    void sobeComTodos() {
        runner.run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void naoSobeSemSegredo() {
        for (String nome : new String[] {"FREEMIUM_HASH_SECRET", "FIELD_ENCRYPTION_KEY", "DJANGO_SECRET_KEY"}) {
            for (String valor : new String[] {"", "  "}) {
                runner.withPropertyValues(nome + "=" + valor).run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().rootCause().hasMessageContaining(nome + " ausente ou vazia"));
            }
        }
    }

    @Test
    void naoSobeSemFreemiumHashSecretDefinido() {
        // propriedade ausente de verdade (nao so em branco), desde que o shell nao a exporte
        assumeTrue(System.getenv("FREEMIUM_HASH_SECRET") == null);
        new ApplicationContextRunner().withUserConfiguration(CriptoConfig.class)
                .withPropertyValues(TODOS[0])
                .run(ctx -> assertThat(ctx).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("FREEMIUM_HASH_SECRET ausente ou vazia"));
    }
}
