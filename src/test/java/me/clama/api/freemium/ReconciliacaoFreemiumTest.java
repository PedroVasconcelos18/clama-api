package me.clama.api.freemium;

import static org.assertj.core.api.Assertions.assertThat;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.clama.api.BancoDeTeste;
import me.clama.api.RelogioDeTeste;
import me.clama.api.catalogo.plano.Plano;
import me.clama.api.catalogo.plano.PlanoRepository;
import me.clama.api.comum.agenda.ModoDeExecucao;
import me.clama.api.comum.observabilidade.TransportEmMemoria;
import me.clama.api.pedido.Pedido;
import me.clama.api.pedido.PedidoRepository;
import me.clama.api.pedido.StatusPedido;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@Import({BancoDeTeste.class, RelogioDeTeste.class})
@ActiveProfiles("test")
class ReconciliacaoFreemiumTest {
    static final Instant AGORA = Instant.parse("2026-11-20T15:00:00Z");

    @Autowired JdbcTemplate jdbc;
    @Autowired PedidoRepository pedidos;
    @Autowired PlanoRepository planos;
    @Autowired ReconciliacaoFreemium reconciliacao;
    @Autowired RelogioDeTeste.Fixavel relogio;
    final TransportEmMemoria sentry = new TransportEmMemoria();

    @BeforeEach
    void preparar() {
        BancoDeTeste.exigirContainer(jdbc);
        jdbc.execute("TRUNCATE scheduled_tasks, freemium_freemiumconfirmationtoken, orders_pedido, plans_plan CASCADE");
        relogio.fixar(AGORA);
        Sentry.init(o -> {
            o.setDsn("https://chave@localhost/1");
            o.setTransportFactory(sentry.fabrica());
        });
    }

    @AfterEach
    void desligar() {
        Sentry.close();
        relogio.fixar(null);
    }

    /** created_at gravado direto, relativo ao relogio congelado (o @PrePersist usa o now() do sistema). */
    private UUID pedido(boolean gratuito, StatusPedido status, long segundosAtras, int tokens) {
        Plano plano = new Plano();
        plano.setNome("Gratuito");
        plano.setValorCentavos(0);
        plano.setDescricao("");
        planos.save(plano);
        Pedido p = new Pedido();
        p.setNome("Maria Silva");
        p.setEmail("maria@exemplo.com");
        p.setPlano(plano);
        p.setValorCentavos(0);
        p.setStatus(status);
        UUID id = pedidos.saveAndFlush(p).getId();
        jdbc.update("update orders_pedido set eh_gratuito = ?, created_at = ?::timestamptz, updated_at = ?::timestamptz "
                + "where id = ?", gratuito, AGORA.minusSeconds(segundosAtras).toString(),
                AGORA.minusSeconds(segundosAtras).toString(), id);
        for (int i = 0; i < tokens; i++) {
            jdbc.update("insert into freemium_freemiumconfirmationtoken (id, pedido_id, token, expires_at, used_at, "
                    + "device_hash, created_at, updated_at) values (gen_random_uuid(), ?, ?, now(), ?, '', now(), now())", id,
                    UUID.randomUUID().toString(), i == 0 ? null : java.sql.Timestamp.from(AGORA));
        }
        return id;
    }

    private static final long H = 3600;

    private String status(UUID id) {
        return jdbc.queryForObject("select status from orders_pedido where id = ?", String.class, id);
    }

    private int tokens() {
        return jdbc.queryForObject("select count(*) from freemium_freemiumconfirmationtoken", Integer.class);
    }

    @Test
    void listarNaoAltera() {
        UUID id = pedido(true, StatusPedido.AGUARDANDO_CONFIRMACAO_EMAIL, 49 * H, 1);
        var r = reconciliacao.executar(ModoDeExecucao.LISTAR);
        assertThat(r.resumo()).containsExactly(Map.entry("n_reconciliados", 1), Map.entry("n_tokens_deletados", 0));
        assertThat(status(id)).isEqualTo("aguardando_confirmacao_email");
        assertThat(tokens()).isEqualTo(1);
        assertThat(sentry.eventos).isEmpty();
    }

    @Test
    void executarSemAlertaPorPedido() {
        UUID a = pedido(true, StatusPedido.AGUARDANDO_CONFIRMACAO_EMAIL, 49 * H, 1);
        UUID b = pedido(true, StatusPedido.AGUARDANDO_CONFIRMACAO_EMAIL, 100 * H, 1);
        UUID c = pedido(true, StatusPedido.AGUARDANDO_CONFIRMACAO_EMAIL, 48 * H + 1, 1);
        var r = reconciliacao.executar(ModoDeExecucao.EXECUTAR);
        assertThat(r.resumo()).containsExactly(Map.entry("n_reconciliados", 3), Map.entry("n_tokens_deletados", 3));
        for (UUID id : List.of(a, b, c)) {
            assertThat(status(id)).isEqualTo("erro");
            assertThat(jdbc.queryForObject("select last_error from orders_pedido where id = ?", String.class, id))
                    .isEqualTo("email_confirmacao_nao_entregue");
            assertThat(jdbc.queryForObject("select updated_at = ?::timestamptz from orders_pedido where id = ?",
                    Boolean.class, AGORA.toString(), id)).isTrue();
        }
        assertThat(jdbc.queryForObject("select count(*) from scheduled_tasks where task_name = 'alerta-admin'",
                Integer.class)).isZero();
        assertThat(sentry.eventos).hasSize(1);
        assertThat(sentry.eventos.get(0).getLevel()).isEqualTo(SentryLevel.WARNING);
        assertThat(sentry.eventos.get(0).getMessage().getFormatted())
                .isEqualTo("Freemium: 3 pedidos órfãos reconciliados (>48h em AGUARDANDO_CONFIRMACAO_EMAIL).");
    }

    @Test
    void apagaTodosOsTokensDosOrfaos() {
        UUID orfao = pedido(true, StatusPedido.AGUARDANDO_CONFIRMACAO_EMAIL, 72 * H, 3); // usados e nao usados
        UUID recente = pedido(true, StatusPedido.AGUARDANDO_CONFIRMACAO_EMAIL, H, 1);
        var r = reconciliacao.executar(ModoDeExecucao.EXECUTAR);
        assertThat(r.resumo()).containsExactly(Map.entry("n_reconciliados", 1), Map.entry("n_tokens_deletados", 3));
        assertThat(jdbc.queryForObject("select count(*) from freemium_freemiumconfirmationtoken where pedido_id = ?",
                Integer.class, orfao)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from freemium_freemiumconfirmationtoken where pedido_id = ?",
                Integer.class, recente)).isEqualTo(1);
    }

    @Test
    void pendenteRecenteFica() {
        UUID exato = pedido(true, StatusPedido.AGUARDANDO_CONFIRMACAO_EMAIL, 48 * H, 1); // < estrito
        UUID recente = pedido(true, StatusPedido.AGUARDANDO_CONFIRMACAO_EMAIL, 47 * H, 1);
        UUID pago = pedido(false, StatusPedido.AGUARDANDO_CONFIRMACAO_EMAIL, 99 * H, 1);
        UUID outroStatus = pedido(true, StatusPedido.ERRO, 99 * H, 0);
        var r = reconciliacao.executar(ModoDeExecucao.EXECUTAR);
        assertThat(r.resumo()).containsExactly(Map.entry("n_reconciliados", 0), Map.entry("n_tokens_deletados", 0));
        assertThat(List.of(status(exato), status(recente), status(pago), status(outroStatus)))
                .containsExactly("aguardando_confirmacao_email", "aguardando_confirmacao_email",
                        "aguardando_confirmacao_email", "erro");
        assertThat(tokens()).isEqualTo(3);
        assertThat(sentry.eventos).isEmpty(); // noop: so info
    }
}
