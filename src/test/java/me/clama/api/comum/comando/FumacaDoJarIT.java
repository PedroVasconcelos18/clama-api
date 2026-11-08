package me.clama.api.comum.comando;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import me.clama.api.BancoDeTeste;
import org.junit.jupiter.api.Test;

/**
 * Fumaca do jar empacotado (mapa §9.8; failsafe, depois do package): java -jar ... instituicoes num processo novo, sem
 * o perfil test, sem LANG e com locale C (stdout ASCII), contra um Postgres do Testcontainers so com o schema de
 * producao. Pega o main, o logback e o encoding: o stdout tem so as linhas, com acento, o poller nao sobe e o Flyway
 * nao cria nada. Roda duas vezes: ambiente limpo e ambiente hostil (o railway run injeta toda variavel do servico).
 */
class FumacaDoJarIT {
    /** Variaveis que, sem as travas do Comandos.abrir, venceriam o application-comando.yml (D6, R3, R6, R7). */
    static final Map<String, String> HOSTIL = Map.of("SPRING_FLYWAY_ENABLED", "true", "CLAMA_FILA_SCHEDULER_ATIVO",
            "true", "CLAMA_AGENDA_ATIVA", "true", "SPRING_MAIN_WEB_APPLICATION_TYPE", "servlet", "SPRING_JPA_HIBERNATE_DDL_AUTO",
            "create-drop", "SPRING_SQL_INIT_MODE", "always");

    @Test
    void instituicoesPeloJarSoAsLinhasEmUtf8SemLogNoStdoutSemPollerESemMigrar() throws Exception {
        var banco = BancoDeTeste.container();
        try (Connection c = DriverManager.getConnection(banco.getJdbcUrl(), banco.getUsername(), banco.getPassword())) {
            c.createStatement().execute("""
                    INSERT INTO instituicoes_instituicao (id, created_at, updated_at, nome, logo, ativo, ordem) VALUES
                    ('a1a1a1a1-a1a1-4a1a-8a1a-000000000002', now(), now(), 'KM’s Pela Vida | NÃO DESISTA', '', true, 2),
                    ('a1a1a1a1-a1a1-4a1a-8a1a-000000000001', now(), now(), 'Associação Renal de Mar de Espanha', '', false, 1)
                    """);
        }
        rodarEConferir("fumaca-jar", Map.of());
        rodarEConferir("fumaca-jar-hostil", HOSTIL);
    }

    private static void rodarEConferir(String nome, Map<String, String> extra) throws Exception {
        var banco = BancoDeTeste.container();
        Path alvo = Path.of(System.getProperty("clama.jar")).getParent();
        Path out = alvo.resolve(nome + ".stdout");
        Path err = alvo.resolve(nome + ".stderr");
        ProcessBuilder pb = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(), "-jar",
                System.getProperty("clama.jar"), "instituicoes").redirectOutput(out.toFile()).redirectError(err.toFile());
        Map<String, String> env = pb.environment();
        env.keySet().removeIf(k -> k.equals("LANG") || k.startsWith("LC_") || k.startsWith("JAVA_TOOL_OPTIONS")
                || k.startsWith("JDK_JAVA_OPTIONS") || k.startsWith("SPRING_") || k.startsWith("CLAMA_"));
        // launchd nao poe LANG (no JDK 27 do Mac isso ja da UTF-8); o locale C da US-ASCII no stdout.encoding: o pior
        // caso, em que so o PrintStream UTF-8 do Comandos.rodar salva o acento (R2)
        env.put("LC_ALL", "C");
        env.put("DATABASE_URL", "postgres://" + banco.getUsername() + ":" + banco.getPassword() + "@localhost:"
                + banco.getMappedPort(5432) + "/" + banco.getDatabaseName());
        env.put("FIELD_ENCRYPTION_KEY", "-dPyxte5HDDyxBQExFzkphbnXt2xhs9FWlMLPFQpeuw=");
        env.put("FREEMIUM_HASH_SECRET", "fumaca");
        env.put("DJANGO_SECRET_KEY", "fumaca");
        env.put("MERCADOPAGO_WEBHOOK_SECRET", "fumaca");
        env.put("WORDPRESS_WEBHOOK_SECRET", "fumaca");
        env.put("SENTRY_DSN", "");
        env.putAll(extra);

        Process p = pb.start();
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).as("%s: o jar terminou", nome).isTrue();
        String stderr = Files.readString(err, UTF_8);
        assertThat(p.exitValue()).as("%s: %s", nome, stderr).isZero();
        assertThat(Files.readAllBytes(out)).as("%s: stdout so com as linhas, em UTF-8, nada de log", nome)
                .isEqualTo(("""
                        a1a1a1a1-a1a1-4a1a-8a1a-000000000001\tAssociação Renal de Mar de Espanha
                        a1a1a1a1-a1a1-4a1a-8a1a-000000000002\tKM’s Pela Vida | NÃO DESISTA
                        """).getBytes(UTF_8));
        // o log existe, e foi para o stderr; o poller do db-scheduler loga "Starting scheduler." ao subir
        assertThat(stderr).as(nome).contains("Comando instituicoes: inicio")
                .contains("Comando instituicoes: fim, exit 0").contains("Agenda desligada (clama.agenda.ativa=false)")
                .doesNotContain("Starting scheduler").doesNotContain("Tomcat").doesNotContain("Migrating schema")
                .doesNotContain("drop table").doesNotContain("create table");

        try (Connection c = DriverManager.getConnection(banco.getJdbcUrl(), banco.getUsername(), banco.getPassword());
                ResultSet r = c.createStatement().executeQuery(
                        "SELECT to_regclass('flyway_schema_history') IS NULL, to_regclass('scheduled_tasks') IS NULL")) {
            r.next();
            assertThat(r.getBoolean(1)).as("%s: Flyway nao rodou (R3)", nome).isTrue();
            assertThat(r.getBoolean(2)).as("%s: nem criou a fila", nome).isTrue();
        }
        // create-drop hostil: nada foi apagado (a tabela e as 2 linhas continuam) e o schema segue valido
        try (Connection c = DriverManager.getConnection(banco.getJdbcUrl(), banco.getUsername(), banco.getPassword());
                ResultSet r = c.createStatement().executeQuery("SELECT count(*) FROM instituicoes_instituicao")) {
            r.next();
            assertThat(r.getInt(1)).as("%s: create-drop nao apagou nada", nome).isEqualTo(2);
        }
    }
}
