package me.clama.infra.fila;

import java.lang.annotation.ElementType;
import me.clama.BancoDeTeste;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;

/**
 * Teste que precisa executar jobs: liga o scheduler (parado no perfil de teste) e fecha o contexto no fim da classe. Um
 * contexto em cache com o scheduler ligado seguiria fazendo polling e executaria jobs enfileirados por outros testes.
 * Limpa {@code scheduled_tasks} antes e depois de cada teste ({@link BancoDeTeste#LIMPAR_FILA}).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@TestPropertySource(properties = "clama.fila.iniciar=true")
@DirtiesContext
@Sql(statements = BancoDeTeste.LIMPAR_FILA)
@Sql(statements = BancoDeTeste.LIMPAR_FILA, executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
public @interface FilaLigada {}
