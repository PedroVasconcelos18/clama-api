package me.clama.infra.fila;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * Teste que precisa executar jobs: liga o scheduler (parado no perfil de teste) e fecha o contexto no fim da classe. Um
 * contexto em cache com o scheduler ligado seguiria fazendo polling e executaria jobs enfileirados por outros testes.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@TestPropertySource(properties = "clama.fila.iniciar=true")
@DirtiesContext
public @interface FilaLigada {}
