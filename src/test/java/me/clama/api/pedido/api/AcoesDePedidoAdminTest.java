package me.clama.api.pedido.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import me.clama.api.BancoDeTeste;
import me.clama.api.catalogo.plano.PlanoRepository;
import me.clama.api.comum.fila.Fila;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.token.ServicoDeTokens;
import me.clama.api.oracao.JobGerarOracao;
import me.clama.api.pedido.Pedido;
import me.clama.api.pedido.PedidoRepository;
import me.clama.api.pedido.StatusPedido;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/** Reenviar e marcar gratuito alem do contrato gravado, com o scheduler desligado (os jobs ficam na fila). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class AcoesDePedidoAdminTest {
    @Value("${local.server.port}")
    int porta;
    @Autowired JdbcTemplate jdbc;
    @Autowired UsuarioRepository usuarios;
    @Autowired ServicoDeTokens tokens;
    @Autowired PedidoRepository pedidos;
    @Autowired PlanoRepository planos;
    @Autowired Fila fila;
    @Autowired JobGerarOracao gerar;
    @Autowired TransactionTemplate tx;

    ApiDePedidos api;
    ApiDePedidos.Sessao admin;

    @BeforeEach
    void admin() throws Exception {
        ApiDePedidos.apagarPedidos(jdbc);
        ApiDePedidos.semearCatalogo(jdbc);
        jdbc.update("delete from scheduled_tasks");
        jdbc.update("delete from token_blacklist_blacklistedtoken");
        jdbc.update("delete from token_blacklist_outstandingtoken");
        jdbc.update("delete from users_user");
        Usuario u = new Usuario();
        u.setEmail("admin-acoes@exemplo.com");
        u.setPassword("x");
        u.setClamaAdmin(true);
        u.setActive(true);
        api = new ApiDePedidos(porta);
        admin = api.sessao(tokens.emitirPar(usuarios.saveAndFlush(u)).access());
    }

    private UUID pedido(StatusPedido status, String oracao) {
        Pedido p = new Pedido();
        p.setNome("Maria Silva");
        p.setEmail("maria@exemplo.com");
        p.setPlano(planos.findById(UUID.fromString(ApiDePedidos.P1)).orElseThrow());
        p.setValorCentavos(3000);
        p.setStatus(status);
        p.setOracaoGerada(oracao);
        return pedidos.saveAndFlush(p).getId();
    }

    private String post(UUID id, String acao) throws Exception {
        var r = api.post("/api/admin/pedidos/" + id + "/" + acao + "/", "", admin);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return ApiDePedidos.JSON.readTree(r.body()).get("message").asString();
    }

    private int linhas(String instancia) {
        return jdbc.queryForObject("select count(*) from scheduled_tasks where task_instance = ?", Integer.class,
                instancia);
    }

    /** O 2o POST com o 1o reenvio ainda na fila: o enfileirar devolve false, a resposta e a mesma, um job so. */
    @Test
    void reenviarDuasVezesUmJob() throws Exception {
        UUID id = pedido(StatusPedido.ENVIADA, "Senhor, acolhe a Maria.");
        assertThat(post(id, "reenviar")).isEqualTo("Reenvio disparado");
        assertThat(post(id, "reenviar")).isEqualTo("Reenvio disparado");
        assertThat(linhas("reenviar-oracao/" + id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from scheduled_tasks", Integer.class)).isEqualTo(1);
        assertThat(pedidos.findById(id).orElseThrow().getStatus()).isEqualTo(StatusPedido.ORACAO_GERADA);
    }

    /** aguardando_reenvio: o gerar-oracao reagendado para +300 s vem para agora. */
    @Test
    void reenviarAguardandoReenvioAntecipa() throws Exception {
        UUID id = pedido(StatusPedido.AGUARDANDO_REENVIO, "");
        jdbc.update("update orders_pedido set retry_count = 1, last_error = 'timeout' where id = ?", id);
        tx.executeWithoutResult(s -> fila.enfileirar(gerar, JobGerarOracao.instancia(id), id,
                Instant.now().plusSeconds(300)));

        assertThat(post(id, "reenviar")).isEqualTo("Regeração disparada");
        assertThat(jdbc.queryForObject("select execution_time <= now() from scheduled_tasks where task_instance = ?",
                Boolean.class, JobGerarOracao.instancia(id))).isTrue();
        assertThat(linhas(JobGerarOracao.instancia(id))).isEqualTo(1);
        Pedido p = pedidos.findById(id).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(StatusPedido.PAGO);
        assertThat(p.getRetryCount()).isZero();
        assertThat(p.getLastError()).isEmpty();
    }

    @Test
    void marcarGratuitoEnfileiraGerar() throws Exception {
        UUID id = pedido(StatusPedido.AGUARDANDO_PAGAMENTO, "");
        assertThat(post(id, "marcar-gratuito")).isEqualTo("Pedido marcado como gratuito. Gerando oração.");
        assertThat(jdbc.queryForObject("select convert_from(task_data, 'UTF8') from scheduled_tasks where task_instance = ?",
                String.class, JobGerarOracao.instancia(id))).isEqualTo("\"" + id + "\"");
        Pedido p = pedidos.findById(id).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(StatusPedido.GERANDO_ORACAO);
        assertThat(p.isEhGratuito()).isTrue();
        assertThat(p.getValorCentavos()).isZero();
    }
}
