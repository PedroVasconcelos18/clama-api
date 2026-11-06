package me.clama.api.comum.fila;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * Teste que precisa executar jobs: liga o scheduler (desligado no perfil test) e fecha o contexto no fim da classe.
 * Um contexto em cache com o scheduler ligado segue fazendo polling e executaria os jobs (gerar/enviar/alerta)
 * enfileirados por outros testes.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@TestPropertySource(properties = "clama.fila.scheduler-ativo=true")
@DirtiesContext
public @interface SchedulerLigado {}
