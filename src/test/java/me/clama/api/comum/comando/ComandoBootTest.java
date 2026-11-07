package me.clama.api.comum.comando;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.clama.api.BancoDeTeste;
import me.clama.api.comum.agenda.AgendaConfig;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.web.SecurityFilterChain;

/**
 * O contexto do modo comando, aberto pelo mesmo Comandos.abrir do jar, contra o Testcontainers (mapa §9.8): sem
 * servidor web, sem poller do db-scheduler, sem nada agendado, sem Flyway. O surefire ativa o perfil test, que desliga
 * cada tarefa (modo DESLIGADA); aqui elas voltam para EXECUTAR, para so a chave global clama.agenda.ativa segurar.
 */
class ComandoBootTest {
    static final List<String> TAREFAS = List.of("pedidos-presos", "reconciliar-freemium", "reconciliar-espelho",
            "monitorar-seo", "verificar-urls", "alerta-comentarios", "purgar-ips");

    @Test
    void sobeSemWebSemFilaSemAgendaSemFlyway() {
        var banco = BancoDeTeste.container();
        Map<String, Object> props = new HashMap<>(Map.of("spring.datasource.url", banco.getJdbcUrl(),
                "spring.datasource.username", banco.getUsername(), "spring.datasource.password", banco.getPassword()));
        TAREFAS.forEach(t -> props.put("clama.agenda." + t + ".modo", "EXECUTAR"));
        // com contextos de teste ja abertos nesta JVM o logback ja esta configurado (perfil test) e o Boot nao
        // reconfigura; desmarcado, o contexto do comando carrega o logback-spring.xml como no processo do jar
        LoggingSystem.get(ComandoBootTest.class.getClassLoader()).cleanUp();
        try (ConfigurableApplicationContext ctx = Comandos.abrir(props)) {
            assertThat(ctx.getEnvironment().getActiveProfiles()).contains("comando");
            assertThat(ctx).isNotInstanceOf(WebServerApplicationContext.class);
            assertThat(ctx.getBeanNamesForType(SecurityFilterChain.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(Scheduler.class)).as("poller do db-scheduler").isEmpty();
            assertThat(ctx.getBean(SchedulerClient.class)).as("enfileirar continua possivel").isNotNull();
            assertThat(ctx.getBean(AgendaConfig.class).nomes()).containsExactlyInAnyOrderElementsOf(TAREFAS);
            ThreadPoolTaskScheduler agenda = ctx.getBean("agendaScheduler", ThreadPoolTaskScheduler.class);
            assertThat(agenda.getScheduledThreadPoolExecutor().getQueue()).as("nada agendado").isEmpty();
            assertThat(ctx.getBeanNamesForType(Flyway.class)).as("Flyway nunca roda no modo comando").isEmpty();
            assertThat(ctx.getEnvironment().getProperty("spring.flyway.enabled")).isEqualTo("false");
            // R1 no logback (alem do System.setOut do Comandos.rodar): o console do perfil comando e o stderr
            var raiz = ((ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory())
                    .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            assertThat(((ch.qos.logback.core.ConsoleAppender<?>) raiz.getAppender("CONSOLE")).getTarget())
                    .isEqualTo("System.err");

            // e um comando roda nesse contexto
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int exit = Comandos.executar(new String[] {"instituicoes"}, new PrintStream(out, true, UTF_8),
                    new PrintStream(err, true, UTF_8), ComandosTest.stdin(null), Path.of("."), ctx);
            assertThat(exit).as(err.toString(UTF_8)).isZero();
        }
    }
}
