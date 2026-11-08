package me.clama.api.instituicao.repasse.comando;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.sql.DataSource;
import me.clama.api.BancoDeTeste;
import me.clama.api.RelogioDeTeste;
import me.clama.api.comum.cripto.Fernet;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.instituicao.repasse.RegistroDeRepasse;
import me.clama.api.instituicao.repasse.comando.LedgerDeTeste.Saida;
import me.clama.api.pedido.PedidoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * TestQuitacao (test_auditoria_repasse.py) portado, mais o que o Java corrige (mapa §4.2): UPDATE condicional (duas
 * quitacoes ao mesmo tempo: uma quita, a outra falha sem gravar), comprovante ate 120, data local na lista (D14) e
 * django_admin_log com --autor.
 */
@SpringBootTest
@Import({BancoDeTeste.class, RelogioDeTeste.class})
@ActiveProfiles("test")
class MarcarRepasseTransferidoTest {
    static final Instant AGORA = Instant.parse("2026-10-28T13:00:00Z");

    @Autowired JdbcTemplate jdbc;
    @Autowired Fernet fernet;
    @Autowired ApplicationContext ctx;
    @Autowired RelogioDeTeste.Fixavel relogio;
    @Autowired DataSource dataSource;
    @Autowired RegistroDeRepasse registro;
    @Autowired PedidoRepository pedidos;
    @Autowired UsuarioRepository usuarios;
    @Autowired TransactionTemplate tx;

    @TempDir java.nio.file.Path dir;
    LedgerDeTeste db;
    UUID casa;

    @BeforeEach
    void limpar() {
        db = new LedgerDeTeste(jdbc, fernet, ctx);
        db.limpar();
        casa = db.instituicao("Casa de Apoio");
        relogio.fixar(AGORA);
    }

    @AfterEach
    void soltar() {
        relogio.fixar(null);
    }

    /** _repasse: pedido enviado de 5000 com id de pagamento, repasse de 1000. */
    private UUID repasse() {
        UUID pedido = db.pedido(p -> { p.instituicao = casa; p.status = "enviada"; p.valor = 5000; p.mp = "MP-1"; });
        return db.repasse(casa, r -> { r.pedido = pedido; r.base = 5000; r.valor = 1000; });
    }

    private Map<String, Object> linha(UUID repasse) {
        return jdbc.queryForMap("select status, comprovante, transferido_em from instituicoes_repasse where id = ?",
                repasse);
    }

    private Saida marcar(String... args) {
        return db.rodar(dir, Stream.concat(Stream.of("marcar_repasse_transferido", "--instituicao=Casa de Apoio"),
                Arrays.stream(args)).toArray(String[]::new));
    }

    @Test
    void nascePendente() {
        UUID pedido = db.pedido(p -> { p.instituicao = casa; p.valor = 5000; });
        Boolean gravou = tx.execute(st -> registro.registrar(pedidos.detalhe(pedido).orElseThrow()));
        assertThat(gravou).isTrue();

        assertThat(jdbc.queryForMap("select status, comprovante, transferido_em from instituicoes_repasse "
                + "where pedido_id = ?", pedido)).containsEntry("status", "registrado").containsEntry("comprovante", "")
                .containsEntry("transferido_em", null);
    }

    @Test
    void marcarComoTransferidoGravaComprovante() {
        UUID r = repasse();

        assertThat(marcar("--comprovante=E123-PIX", "--confirmar").exit()).isZero();

        Map<String, Object> l = linha(r);
        assertThat(l).containsEntry("status", "transferido").containsEntry("comprovante", "E123-PIX");
        assertThat(((java.sql.Timestamp) l.get("transferido_em")).toInstant()).isEqualTo(AGORA); // Clock injetado
    }

    /** Pagar o mesmo repasse duas vezes e o erro caro que isso previne (o UPDATE condicional faz o papel da guarda do model). */
    @Test
    void recusaQuitarDuasVezes() {
        UUID r = repasse();
        marcar("--comprovante=E123-PIX", "--confirmar");

        Saida s = marcar("--comprovante=E999-OUTRO", "--confirmar");

        assertThat(s.exit()).isZero();
        assertThat(linha(r)).containsEntry("comprovante", "E123-PIX");
    }

    @Test
    void comandoSimulaPorPadrao() {
        UUID r = repasse();

        Saida s = marcar();

        assertThat(s.exit()).isZero();
        assertThat(linha(r)).containsEntry("status", "registrado");
        assertThat(s.out()).contains("SIMULAÇÃO");
    }

    @Test
    void comandoExigeComprovanteParaConfirmar() {
        UUID r = repasse();

        Saida s = marcar("--confirmar");

        assertThat(s.exit()).isEqualTo(1);
        assertThat(s.err()).startsWith("CommandError: --comprovante é obrigatório");
        assertThat(linha(r)).containsEntry("status", "registrado");
    }

    @Test
    void comandoQuitaComConfirmar() {
        UUID r = repasse();

        Saida s = marcar("--comprovante=E123-PIX", "--confirmar");

        assertThat(linha(r)).containsEntry("status", "transferido").containsEntry("comprovante", "E123-PIX");
        assertThat(s.out()).endsWith("\n1 repasse(s) quitado(s), R$ 10,00, comprovante 'E123-PIX'.\n");
        assertThat(jdbc.queryForObject("select count(*) from django_admin_log", Integer.class)).isZero(); // sem --autor
    }

    @Test
    void naoRequitaOJaTransferido() {
        UUID r = repasse();
        db.transferir(r, "E123-PIX");

        Saida s = marcar("--comprovante=E999", "--confirmar");

        assertThat(linha(r)).containsEntry("comprovante", "E123-PIX");
        assertThat(s.out()).contains("Nenhum repasse pendente");
    }

    // --- corrigido no Java ---

    /** D11: comprovante cabe no varchar(120); o Django estourava DataError (traceback) depois de imprimir a lista. */
    @Test
    void comprovanteAcimaDe120ERecusadoSemGravar() {
        UUID r = repasse();

        Saida s = marcar("--comprovante=" + "ç".repeat(121), "--confirmar");

        assertThat(s.exit()).isEqualTo(1);
        assertThat(s.err()).isEqualTo("CommandError: --comprovante tem 121 caracteres; o máximo é 120. "
                + "Nada foi gravado.\n");
        assertThat(linha(r)).containsEntry("status", "registrado");
        assertThat(marcar("--comprovante=" + "ç".repeat(120), "--confirmar").exit()).isZero();
    }

    /** D14: 22h30 de 04/10 em Sao Paulo e 01h30 de 05/10 em UTC; a lista mostra o dia local (o Django, o UTC). */
    @Test
    void listaMostraODiaLocal() {
        UUID pedido = db.pedido(p -> p.instituicao = casa);
        db.repasse(casa, r -> { r.pedido = pedido; r.criado = LedgerDeTeste.em(LocalDateTime.of(2026, 10, 4, 22, 30));
            r.valor = 500; });

        assertThat(marcar("--mes=2026-10").out()).contains("  04/10/2026     R$ 5,00  pedido " + pedido + "\n");
    }

    /** --autor: um django_admin_log por repasse quitado, na transacao da quitacao. */
    @Test
    void autorGravaAuditoria() {
        Usuario u = new Usuario();
        u.setEmail("tesouraria@exemplo.com");
        u.setPassword("x");
        long autor = usuarios.saveAndFlush(u).getId();
        jdbc.update("update users_user set is_staff = true where id = ?", autor);
        jdbc.update("insert into django_content_type (app_label, model) values ('instituicoes', 'repasse') "
                + "on conflict do nothing");
        UUID a = repasse();
        UUID b = repasse();

        assertThat(marcar("--comprovante=E1", "--confirmar", "--autor=" + autor).exit()).isZero();

        assertThat(jdbc.queryForList("""
                select l.object_id, l.user_id, l.action_flag, l.change_message, l.action_time
                from django_admin_log l join django_content_type c on c.id = l.content_type_id
                where c.app_label = 'instituicoes' and c.model = 'repasse' order by l.object_id""")).hasSize(2)
                .allSatisfy(l -> {
                    assertThat(l.get("user_id")).isEqualTo(autor);
                    assertThat(l.get("action_flag")).isEqualTo(2);
                    assertThat(l.get("change_message"))
                            .isEqualTo("[{\"changed\":{\"fields\":[\"Status\",\"Transferido em\",\"Comprovante\"]}}]");
                    assertThat(((java.sql.Timestamp) l.get("action_time")).toInstant()).isEqualTo(AGORA);
                })
                .extracting(l -> l.get("object_id")).containsExactlyInAnyOrder(a.toString(), b.toString());
    }

    @Test
    void autorInexistenteOuInvalidoNaoQuita() {
        UUID r = repasse();

        assertThat(marcar("--comprovante=E1", "--confirmar", "--autor=999999").err())
                .isEqualTo("CommandError: Nenhum usuário staff ativo com o id 999999.\n");
        assertThat(marcar("--comprovante=E1", "--confirmar", "--autor=ana").err())
                .isEqualTo("CommandError: --autor inválido: 'ana'. Use o id numérico de um usuário.\n");
        assertThat(linha(r)).containsEntry("status", "registrado");
    }

    /**
     * Duas quitacoes do mesmo repasse ao mesmo tempo: as duas leem "registrado" e imprimem a lista; uma trava segura as
     * linhas ate as duas estarem esperando no UPDATE. Solta: uma quita, a outra ve a contagem errada, falha com
     * CommandError e nao grava nada (no Django a segunda sobrescrevia comprovante e transferido_em sem erro).
     */
    @Test
    void quitacaoConcorrente() throws Exception {
        UUID a = repasse();
        UUID b = repasse();
        CompletableFuture<Saida> primeira;
        CompletableFuture<Saida> segunda;
        try (Connection trava = dataSource.getConnection()) {
            trava.setAutoCommit(false);
            trava.createStatement().execute("select id from instituicoes_repasse for update");
            primeira = CompletableFuture.supplyAsync(() -> marcar("--comprovante=PIX-A", "--confirmar"));
            segunda = CompletableFuture.supplyAsync(() -> marcar("--comprovante=PIX-B", "--confirmar"));
            long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (esperandoTrava() < 2) {
                assertThat(System.nanoTime()).as("as duas quitacoes no UPDATE").isLessThan(limite);
                Thread.onSpinWait();
            }
            trava.rollback();
        }
        List<Saida> saidas = List.of(primeira.get(30, TimeUnit.SECONDS), segunda.get(30, TimeUnit.SECONDS));

        assertThat(saidas).extracting(Saida::exit).containsExactlyInAnyOrder(0, 1);
        Saida venceu = saidas.stream().filter(s -> s.exit() == 0).findFirst().orElseThrow();
        Saida perdeu = saidas.stream().filter(s -> s.exit() == 1).findFirst().orElseThrow();
        assertThat(perdeu.out()).contains("2 repasse(s), total R$ 20,00").doesNotContain("quitado(s)");
        assertThat(perdeu.err()).isEqualTo("CommandError: 2 de 2 repasse(s) já não estavam pendentes (outra quitação "
                + "ao mesmo tempo?). Nada foi gravado; rode de novo para ver o que falta.\n");
        String comprovante = venceu.out().contains("'PIX-A'") ? "PIX-A" : "PIX-B";
        assertThat(List.of(linha(a), linha(b))).allSatisfy(l -> assertThat(l)
                .containsEntry("status", "transferido").containsEntry("comprovante", comprovante));
    }

    /**
     * Quitacao parcial: com dois repasses no periodo, a trava segura o UPDATE do comando; nela mesma (outra transacao
     * que nao a do comando) um dos dois e quitado e confirmado. Solta, a contagem nao fecha (1 de 2): falha, o outro
     * continua registrado e, com --autor, nao sobra django_admin_log.
     */
    @Test
    void quitacaoParcialFazRollbackSemAuditoria() throws Exception {
        Usuario u = new Usuario();
        u.setEmail("tesouraria@exemplo.com");
        u.setPassword("x");
        long autor = usuarios.saveAndFlush(u).getId();
        jdbc.update("update users_user set is_staff = true where id = ?", autor);
        jdbc.update("insert into django_content_type (app_label, model) values ('instituicoes', 'repasse') "
                + "on conflict do nothing");
        UUID a = repasse();
        UUID b = repasse();
        CompletableFuture<Saida> comando;
        try (Connection trava = dataSource.getConnection()) {
            trava.setAutoCommit(false);
            trava.createStatement().execute("select id from instituicoes_repasse for update");
            comando = CompletableFuture.supplyAsync(() -> marcar("--comprovante=PIX-CMD", "--confirmar", "--autor=" + autor));
            long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (esperandoTrava() < 1) {
                assertThat(System.nanoTime()).as("o comando no UPDATE").isLessThan(limite);
                Thread.onSpinWait();
            }
            try (var quita = trava.prepareStatement("update instituicoes_repasse set status = 'transferido', "
                    + "comprovante = 'PIX-OUTRA' where id = ?")) {
                quita.setObject(1, a);
                quita.executeUpdate();
            }
            trava.commit();
        }
        Saida s = comando.get(30, TimeUnit.SECONDS);

        assertThat(s.exit()).as(s.err()).isEqualTo(1);
        assertThat(s.err()).startsWith("CommandError: 1 de 2 repasse(s) já não estavam pendentes");
        assertThat(linha(a)).containsEntry("status", "transferido").containsEntry("comprovante", "PIX-OUTRA");
        assertThat(linha(b)).containsEntry("status", "registrado").containsEntry("comprovante", "");
        assertThat(jdbc.queryForObject("select count(*) from django_admin_log", Integer.class)).isZero();
    }

    private int esperandoTrava() {
        return jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type = 'Lock' "
                + "and query like 'update instituicoes_repasse%'", Integer.class);
    }
}
