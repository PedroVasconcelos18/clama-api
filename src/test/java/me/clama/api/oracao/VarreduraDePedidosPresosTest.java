package me.clama.api.oracao;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.clama.api.BancoDeTeste;
import me.clama.api.catalogo.plano.Plano;
import me.clama.api.catalogo.plano.PlanoRepository;
import me.clama.api.comum.agenda.ModoDeExecucao;
import me.clama.api.pedido.Pedido;
import me.clama.api.pedido.PedidoRepository;
import me.clama.api.pedido.StatusPedido;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class VarreduraDePedidosPresosTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PedidoRepository pedidos;
    @Autowired PlanoRepository planos;
    @Autowired VarreduraDePedidosPresos varredura;
    @Autowired AlertaDeErro alerta;

    @BeforeEach
    void limpar() {
        BancoDeTeste.exigirContainer(jdbc);
        jdbc.execute("TRUNCATE scheduled_tasks, orders_pedido, plans_plan CASCADE");
        alerta.limpar();
        varredura.aposLeitura = () -> { };
    }

    /** updated_at relativo ao relogio do BANCO (o do container pode destoar do da JVM). */
    private UUID pedido(StatusPedido status, int minutosAtras) {
        Plano plano = new Plano();
        plano.setNome("Pedido de Oração");
        plano.setValorCentavos(2000);
        plano.setDescricao("");
        planos.save(plano);
        Pedido p = new Pedido();
        p.setNome("Maria Silva");
        p.setEmail("maria@exemplo.com");
        p.setPlano(plano);
        p.setValorCentavos(3000);
        p.setStatus(status);
        UUID id = pedidos.saveAndFlush(p).getId();
        jdbc.update("update orders_pedido set updated_at = now() - make_interval(mins => ?) where id = ?", minutosAtras, id);
        return id;
    }

    private String status(UUID id) {
        return jdbc.queryForObject("select status from orders_pedido where id = ?", String.class, id);
    }

    private int alertas() {
        return jdbc.queryForObject("select count(*) from scheduled_tasks where task_name = 'alerta-admin'", Integer.class);
    }

    private void fila(String nome, UUID id) {
        jdbc.update("insert into scheduled_tasks (task_name, task_instance, execution_time, picked, version) "
                + "values (?, ?, now() + interval '1 day', false, 1)", nome, nome + "/" + id);
    }

    @Test
    void listarNaoAltera() {
        UUID id = pedido(StatusPedido.PAGO, 30);
        var r = varredura.executar(ModoDeExecucao.LISTAR);
        assertThat(r.resumo()).containsExactly(Map.entry("presos", 1), Map.entry("marcados", 0));
        assertThat(status(id)).isEqualTo("pago");
        assertThat(alertas()).isZero();
    }

    @Test
    void executarMarcaErroEAlerta() {
        UUID id = pedido(StatusPedido.PAGO, 30);
        var r = varredura.executar(ModoDeExecucao.EXECUTAR);
        assertThat(r.resumo()).containsExactly(Map.entry("presos", 1), Map.entry("marcados", 1));
        assertThat(status(id)).isEqualTo("erro");
        assertThat(jdbc.queryForObject("select last_error from orders_pedido where id = ?", String.class, id))
                .isEqualTo("pedido_preso");
        assertThat(alertas()).isEqualTo(1);
    }

    @Test
    void pedidoComJobNaFilaNaoEPreso() {
        UUID a = pedido(StatusPedido.GERANDO_ORACAO, 30);
        UUID b = pedido(StatusPedido.ORACAO_GERADA, 30);
        UUID c = pedido(StatusPedido.AGUARDANDO_REENVIO, 30);
        fila("gerar-oracao", a);
        fila("enviar-oracao", b);
        fila("reenviar-oracao", c);
        var r = varredura.executar(ModoDeExecucao.EXECUTAR);
        assertThat(r.resumo().get("presos")).isEqualTo(0);
        assertThat(List.of(status(a), status(b), status(c)))
                .containsExactly("gerando_oracao", "oracao_gerada", "aguardando_reenvio");
    }

    /** Job de outro nome com a instancia parecida nao protege o pedido (igualdade exata). */
    @Test
    void instanciaDeOutroJobNaoProtege() {
        UUID id = pedido(StatusPedido.PAGO, 30);
        fila("alerta-admin", id);
        assertThat(varredura.executar(ModoDeExecucao.EXECUTAR).resumo().get("marcados")).isEqualTo(1);
    }

    @Test
    void pedidoRecenteNaoEPreso() {
        UUID id = pedido(StatusPedido.PAGO, 5);
        UUID ok = pedido(StatusPedido.ENVIADA, 300);
        assertThat(varredura.executar(ModoDeExecucao.EXECUTAR).resumo().get("presos")).isEqualTo(0);
        assertThat(status(id)).isEqualTo("pago");
        assertThat(status(ok)).isEqualTo("enviada");
    }

    @Test
    void varreduraNaoMarcaPedidoQueAndou() {
        UUID id = pedido(StatusPedido.PAGO, 30);
        varredura.aposLeitura = () -> jdbc.update("update orders_pedido set updated_at = now() where id = ?", id);
        var r = varredura.executar(ModoDeExecucao.EXECUTAR);
        assertThat(r.resumo()).containsEntry("presos", 1).containsEntry("marcados", 0);
        assertThat(status(id)).isEqualTo("pago");
        assertThat(alertas()).isZero();
    }
}
