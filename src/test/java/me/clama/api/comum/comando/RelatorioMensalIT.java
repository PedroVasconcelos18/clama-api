package me.clama.api.comum.comando;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import me.clama.api.BancoDeTeste;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Etapa 8, tarefa 4: scripts/relatorio-repasse-mensal.sh com MOTOR=java, de ponta a ponta (failsafe, depois do package):
 * o bash roda o script de verdade, que chama o jar empacotado oito vezes contra o Postgres do Testcontainers semeado
 * com os "dados" dos cenarios cmd_relatorio_repasse_rotina_* (a rotina de 2026-10-28). Sem railway (EXEC_PREFIX vazio) e
 * sem notificacao (osascript falso no PATH). Confere os 6 CSVs byte a byte com os gravados do Django, a ausencia do CSV
 * do ledger (sem achado), e a guarda de instituicoes do log: com as 5 do cenario ela avisa; com 3 fica calada.
 */
class RelatorioMensalIT {
    static final Path CENARIOS = Path.of("src/test/resources/contrato/comandos");
    static final ObjectMapper JSON = new ObjectMapper();
    static final List<String> ROTINA = List.of("juiz_de_fora", "mar_de_espanha", "pela_vida", "auditoria_juiz_de_fora",
            "auditoria_mar_de_espanha", "auditoria_pela_vida");

    @Test
    void scriptMensalComMotorJavaGeraOsCsvsDoDjangoEConfereAGuardaDeInstituicoes() throws Exception {
        var banco = BancoDeTeste.container();
        Map<String, byte[]> esperados = new TreeMap<>();
        JsonNode dados = null;
        for (String nome : ROTINA) {
            JsonNode c = JSON.readTree(CENARIOS.resolve("cmd_relatorio_repasse_rotina_" + nome + ".json").toFile());
            dados = c.get("dados");
            c.get("arquivos").properties().forEach(a -> esperados.put(a.getKey(),
                    Base64.getDecoder().decode(a.getValue().asString())));
        }
        assertThat(esperados).hasSize(6);

        try (Connection c = DriverManager.getConnection(banco.getJdbcUrl(), banco.getUsername(), banco.getPassword())) {
            limpar(c);
            try {
                semear(c, dados);
                // 5 instituicoes no banco, 3 na lista do script: a guarda avisa; os CSVs saem iguais mesmo assim
                String log = rodar("cinco", esperados);
                assertThat(log).contains("\n!!! O banco tem '5' instituições e a lista INSTITUICOES tem 3.\n")
                        .contains("\n>>> Banco tem 5 instituições, script cobre 3 — atualize INSTITUICOES\n");

                c.createStatement().execute("DELETE FROM instituicoes_instituicao WHERE ordem > 3");
                log = rodar("tres", esperados);
                assertThat(log).doesNotContain("!!!").doesNotContain("CRÍTICO")
                        .contains("\n>>> Relatórios das 3 instituições prontos em clama/relatorios\n");
            } finally {
                limpar(c); // o banco e compartilhado com o FumacaDoJarIT
            }
        }
    }

    private static String rodar(String nome, Map<String, byte[]> esperados) throws Exception {
        var banco = BancoDeTeste.container();
        Path alvo = Path.of(System.getProperty("clama.jar")).toAbsolutePath().getParent();
        Path relatorios = Files.createTempDirectory(alvo, "relatorios-" + nome);
        Path fake = Files.createTempDirectory(alvo, "fake-bin");
        Path osascript = fake.resolve("osascript");
        Files.writeString(osascript, "#!/bin/sh\necho \"$@\" > \"" + fake + "/notificacao\"\n");
        Files.setPosixFilePermissions(osascript, PosixFilePermissions.fromString("rwxr-xr-x"));

        ProcessBuilder pb = new ProcessBuilder("/bin/bash", "scripts/relatorio-repasse-mensal.sh").redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.keySet().removeIf(k -> k.equals("LANG") || k.startsWith("LC_") || k.startsWith("JAVA_TOOL_OPTIONS")
                || k.startsWith("JDK_JAVA_OPTIONS") || k.startsWith("SPRING_") || k.startsWith("CLAMA_"));
        env.put("LC_ALL", "C"); // como o launchd: sem LANG (R2)
        env.put("PATH", fake + ":" + env.get("PATH"));
        env.put("MOTOR", "java");
        env.put("JAVA", ProcessHandle.current().info().command().orElseThrow());
        env.put("JAR", System.getProperty("clama.jar"));
        env.put("API", Path.of("").toAbsolutePath().toString());
        env.put("EXEC_PREFIX", ""); // sem railway: o jar le o ambiente abaixo
        env.put("RELATORIOS", relatorios.toString());
        env.put("HOJE", "2026-10-28");
        env.put("MES", "2026-10");
        env.put("DESDE", "2026-09-28");
        env.put("ATE", "2026-10-27");
        env.put("DATABASE_URL", "postgres://" + banco.getUsername() + ":" + banco.getPassword() + "@localhost:"
                + banco.getMappedPort(5432) + "/" + banco.getDatabaseName());
        env.put("FIELD_ENCRYPTION_KEY", "-dPyxte5HDDyxBQExFzkphbnXt2xhs9FWlMLPFQpeuw=");
        env.put("FREEMIUM_HASH_SECRET", "script");
        env.put("DJANGO_SECRET_KEY", "script");
        env.put("MERCADOPAGO_WEBHOOK_SECRET", "script");
        env.put("WORDPRESS_WEBHOOK_SECRET", "script");
        env.put("SENTRY_DSN", "");

        Process p = pb.start();
        byte[] saida = p.getInputStream().readAllBytes(); // EOF so quando o tee do script tambem terminou
        assertThat(p.waitFor(300, TimeUnit.SECONDS)).as("%s: o script terminou", nome).isTrue();
        String stdout = new String(saida, UTF_8);
        assertThat(p.exitValue()).as("%s: %s", nome, stdout).isZero();

        // o log do arquivo e o stdout do script sao o mesmo texto
        Path log = relatorios.resolve("log-2026-10.txt");
        assertThat(Files.readString(log, UTF_8)).as("%s: log", nome).isEqualTo(stdout);
        assertThat(stdout).as(nome).contains("--- auditoria do ledger (todas as instituições) ---")
                .doesNotContain("Traceback").doesNotContain("Exception");
        assertThat(Files.readString(fake.resolve("notificacao"), UTF_8)).as("%s: notificacao", nome)
                .contains("Repasse — dia 28");

        // exatamente os 6 CSVs do Django (sem auditoria-ledger: nao ha achado) e o log, byte a byte
        List<String> nomes = new ArrayList<>();
        try (Stream<Path> s = Files.list(relatorios)) {
            s.forEach(f -> nomes.add(f.getFileName().toString()));
        }
        nomes.remove("log-2026-10.txt");
        assertThat(nomes).as("%s: arquivos gravados", nome).containsExactlyInAnyOrderElementsOf(esperados.keySet());
        for (Map.Entry<String, byte[]> e : esperados.entrySet()) {
            byte[] g = Files.readAllBytes(relatorios.resolve(e.getKey()));
            assertThat(new String(g, UTF_8)).as("%s: %s", nome, e.getKey()).isEqualTo(new String(e.getValue(), UTF_8));
            assertThat(g).as("%s: bytes de %s", nome, e.getKey()).isEqualTo(e.getValue());
        }
        return stdout;
    }

    private static void limpar(Connection c) throws Exception {
        c.createStatement().execute(
                "DELETE FROM instituicoes_repasse; DELETE FROM orders_pedido; DELETE FROM instituicoes_instituicao;"
                        + " DELETE FROM plans_plan");
    }

    /** Linhas de "dados" como o ReplayDeContrato.inserir: texto de tipo nao especificado, o Postgres converte. */
    private static void semear(Connection c, JsonNode dados) throws Exception {
        for (String tabela : List.of("plans_plan", "instituicoes_instituicao")) {
            for (JsonNode linha : dados.get(tabela)) {
                List<String> colunas = new ArrayList<>();
                List<String> valores = new ArrayList<>();
                for (Map.Entry<String, JsonNode> e : linha.properties()) {
                    colunas.add(e.getKey());
                    JsonNode v = e.getValue();
                    valores.add(v.isNull() ? null : v.isString() ? v.asString() : v.toString());
                }
                String sql = "INSERT INTO " + tabela + " (" + String.join(", ", colunas)
                        + ", created_at, updated_at) VALUES (" + String.join(", ", java.util.Collections.nCopies(colunas.size(), "?"))
                        + ", clock_timestamp(), clock_timestamp())";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    for (int k = 0; k < valores.size(); k++) {
                        ps.setObject(k + 1, valores.get(k), Types.OTHER);
                    }
                    ps.executeUpdate();
                }
            }
        }
        assertThat(dados.get("orders_pedido")).isEmpty(); // a rotina de 28/10 nao tem repasse no ciclo
    }
}
