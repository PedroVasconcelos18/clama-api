package me.clama.api.freemium;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import me.clama.api.BancoDeTeste;
import me.clama.api.RelogioDeTeste;
import me.clama.api.catalogo.plano.Plano;
import me.clama.api.catalogo.plano.PlanoRepository;
import me.clama.api.comum.email.CaixaDeSaidaEmMemoria;
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
import org.springframework.transaction.support.TransactionTemplate;

/** O job de confirmacao chamado direto (sem o agendador); o replay do Django esta no ContratoFreemiumTest. */
@SpringBootTest(properties = {"FRONTEND_BASE_URL=https://clama.me/"})
@Import({BancoDeTeste.class, RelogioDeTeste.class})
@ActiveProfiles("test")
class JobConfirmacaoFreemiumTest {
    static final Instant AGORA = Instant.parse("2026-11-20T15:00:00Z");

    @Autowired JobConfirmacaoFreemium job;
    @Autowired PedidoRepository pedidos;
    @Autowired PlanoRepository planos;
    @Autowired TokenDeConfirmacaoRepository tokens;
    @Autowired CaixaDeSaidaEmMemoria caixa;
    @Autowired RelogioDeTeste.Fixavel relogio;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void limpar() {
        BancoDeTeste.exigirContainer(jdbc);
        jdbc.execute("TRUNCATE scheduled_tasks, freemium_freemiumconfirmationtoken, orders_pedido, plans_plan CASCADE");
        caixa.limpar();
        relogio.fixar(AGORA);
    }

    @AfterEach
    void soltar() {
        jdbc.execute("TRUNCATE scheduled_tasks, freemium_freemiumconfirmationtoken, orders_pedido, plans_plan CASCADE");
        relogio.fixar(null);
    }

    private UUID pedido(StatusPedido status, Instant expira, boolean usado) {
        return tx.execute(s -> {
            Plano plano = new Plano();
            plano.setNome("Gratuito");
            plano.setValorCentavos(0);
            plano.setDescricao("");
            planos.save(plano);
            Pedido p = new Pedido();
            p.setNome("Maria Silva");
            p.setEmail("maria@exemplo.com");
            p.setPlano(plano);
            p.setEhGratuito(true);
            p.setStatus(status);
            pedidos.save(p);
            TokenDeConfirmacao t = new TokenDeConfirmacao();
            t.setToken("tok-" + p.getId());
            t.setPedido(p);
            t.setExpiresAt(expira.atOffset(ZoneOffset.UTC));
            if (usado) t.setUsedAt(OffsetDateTime.ofInstant(AGORA, ZoneOffset.UTC));
            tokens.save(t);
            return p.getId();
        });
    }

    private Pedido ler(UUID id) {
        return pedidos.findById(id).orElseThrow();
    }

    private int alertas(UUID id) {
        return jdbc.queryForObject("select count(*) from scheduled_tasks where task_instance = ?", Integer.class,
                "alerta-admin/" + id);
    }

    @Test
    void tokenExpiradoVaiParaErroComAlerta() {
        UUID id = pedido(StatusPedido.AGUARDANDO_CONFIRMACAO_EMAIL, AGORA, false); // expires_at <= agora = vencido
        job.executar(id, 0);
        assertThat(caixa.enviados()).isEmpty();
        assertThat(ler(id).getStatus()).isEqualTo(StatusPedido.ERRO);
        assertThat(ler(id).getLastError()).isEqualTo("token_expirado_antes_de_envio");
        assertThat(alertas(id)).isEqualTo(1);
    }

    @Test
    void tokenUsadoTambemVaiParaErro() {
        UUID id = pedido(StatusPedido.AGUARDANDO_CONFIRMACAO_EMAIL, AGORA.plusSeconds(3600), true);
        job.executar(id, 0);
        assertThat(caixa.enviados()).isEmpty();
        assertThat(ler(id).getStatus()).isEqualTo(StatusPedido.ERRO);
    }

    @Test
    void statusMudouNaoEnvia() {
        UUID id = pedido(StatusPedido.GERANDO_ORACAO, AGORA.plusSeconds(3600), false);
        job.executar(id, 0);
        assertThat(caixa.enviados()).isEmpty();
        assertThat(ler(id).getStatus()).isEqualTo(StatusPedido.GERANDO_ORACAO);
        assertThat(alertas(id)).isZero();
    }

    /** A retentativa reenvia a mesma chave da Resend ({@code confirmacao-freemium/<id>}): o 2o envio e "ja enviado". */
    @Test
    void reenvioDoJobUsaMesmaChave() {
        UUID id = pedido(StatusPedido.AGUARDANDO_CONFIRMACAO_EMAIL, AGORA.plusSeconds(3600), false);
        job.executar(id, 0);
        job.executar(id, 1);
        assertThat(caixa.enviados()).hasSize(1);
        assertThat(caixa.enviados().get(0).chaveDeIdempotencia()).isEqualTo("confirmacao-freemium/" + id);
        assertThat(caixa.enviados().get(0).texto()).contains("https://clama.me/confirmar?token=tok-" + id);
        assertThat(ler(id).getStatus()).isEqualTo(StatusPedido.AGUARDANDO_CONFIRMACAO_EMAIL);
    }

    @Test
    void esgotouNaoMudaStatus() {
        UUID id = pedido(StatusPedido.AGUARDANDO_CONFIRMACAO_EMAIL, AGORA.plusSeconds(3600), false);
        job.esgotou(id, new IllegalStateException("Resend fora do ar"));
        assertThat(ler(id).getStatus()).isEqualTo(StatusPedido.AGUARDANDO_CONFIRMACAO_EMAIL);
        assertThat(alertas(id)).isZero();
    }
}
