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
 * o perfil test, sem LANG e com locale C (stdout ASCII), contra um Postgres do Testcontainers so com o schema de producao. Pega o
 * main, o logback e o encoding: o stdout tem so as linhas, com acento, e o Flyway nao criou nada.
 */
class FumacaDoJarIT {
    @Test
    void instituicoesPeloJarSoAsLinhasEmUtf8SemLogNoStdoutESemMigrar() throws Exception {
        var banco = BancoDeTeste.container();
        try (Connection c = DriverManager.getConnection(banco.getJdbcUrl(), banco.getUsername(), banco.getPassword())) {
            c.createStatement().execute("""
                    INSERT INTO instituicoes_instituicao (id, created_at, updated_at, nome, logo, ativo, ordem) VALUES
                    ('a1a1a1a1-a1a1-4a1a-8a1a-000000000002', now(), now(), 'KM’s Pela Vida | NÃO DESISTA', '', true, 2),
                    ('a1a1a1a1-a1a1-4a1a-8a1a-000000000001', now(), now(), 'Associação Renal de Mar de Espanha', '', false, 1)
                    """);
        }
        Path alvo = Path.of(System.getProperty("clama.jar")).getParent();
        Path out = alvo.resolve("fumaca-jar.stdout");
        Path err = alvo.resolve("fumaca-jar.stderr");
        ProcessBuilder pb = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(), "-jar",
                System.getProperty("clama.jar"), "instituicoes").redirectOutput(out.toFile()).redirectError(err.toFile());
        Map<String, String> env = pb.environment();
        env.keySet().removeIf(k -> k.equals("LANG") || k.startsWith("LC_") || k.startsWith("JAVA_TOOL_OPTIONS")
                || k.startsWith("JDK_JAVA_OPTIONS") || k.startsWith("SPRING_"));
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

        Process p = pb.start();
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).as("o jar terminou").isTrue();
        String stderr = Files.readString(err, UTF_8);
        assertThat(p.exitValue()).as(stderr).isZero();
        assertThat(Files.readAllBytes(out)).as("stdout: so as linhas, em UTF-8, nada de log").isEqualTo(("""
                a1a1a1a1-a1a1-4a1a-8a1a-000000000001\tAssociação Renal de Mar de Espanha
                a1a1a1a1-a1a1-4a1a-8a1a-000000000002\tKM’s Pela Vida | NÃO DESISTA
                """).getBytes(UTF_8));
        // o log existe, e foi para o stderr
        assertThat(stderr).contains("Comando instituicoes: inicio").contains("Comando instituicoes: fim, exit 0")
                .contains("Agenda desligada (clama.agenda.ativa=false)").doesNotContain("Tomcat started");

        try (Connection c = DriverManager.getConnection(banco.getJdbcUrl(), banco.getUsername(), banco.getPassword());
                ResultSet r = c.createStatement().executeQuery(
                        "SELECT to_regclass('flyway_schema_history') IS NULL, to_regclass('scheduled_tasks') IS NULL")) {
            r.next();
            assertThat(r.getBoolean(1)).as("Flyway nao rodou (R3)").isTrue();
            assertThat(r.getBoolean(2)).as("nem criou a fila").isTrue();
        }
    }
}
