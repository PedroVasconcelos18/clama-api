package me.clama.freemium.interno;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import me.clama.BancoDeTeste;
import me.clama.CaixaDeSaidaEmMemoria;
import me.clama.EmailDeTeste;
import me.clama.infra.email.EnvioAssincrono;
import me.clama.pedido.PedidoApi;
import me.clama.pedido.StatusPedido;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@code reconciliar-freemium} (D8): 48 h, dry-run, sem alerta por pedido e a corrida com a confirmacao. */
@SpringBootTest
@Import({ BancoDeTeste.class, EmailDeTeste.class })
@ExtendWith(OutputCaptureExtension.class)
class ReconciliacaoFreemiumTest {

	@Autowired ReconciliacaoFreemium tarefa;
	@Autowired ConfirmacaoFreemium saga;
	@Autowired PedidoApi pedidos;
	@Autowired JdbcTemplate jdbc;
	@Autowired CaixaDeSaidaEmMemoria caixa;
	@Autowired EnvioAssincrono envio;

	@BeforeEach
	@AfterEach
	void limpar() throws Exception {
		for (int i = 0; i < 500 && envio.pendentes() > 0; i++) Thread.sleep(10);
		caixa.limpar();
		jdbc.update(BancoDeTeste.LIMPAR_FILA);
		jdbc.update("delete from pedido");
		jdbc.update("delete from freemium_bloqueio");
		jdbc.update("delete from usuario where email like 'frm-%'");
		FreemiumDeTeste.planoGratuito(jdbc);
	}

	/** Pedido pendente criado {@code idade} atras. */
	UUID pendente(Duration idade) {
		var id = pedidos.criarFreemium(FreemiumDeTeste.novo("frm-" + UUID.randomUUID() + "@exemplo.com", "529.982.247-25"), "203.0.113.9");
		jdbc.update("update pedido set criado_em = ? where id = ?", OffsetDateTime.ofInstant(Instant.now().minus(idade), ZoneOffset.UTC), id);
		return id;
	}

	StatusPedido status(UUID id) {
		return pedidos.resumo(id).orElseThrow().status();
	}

	@Test
	void nomeECronDaTarefa() {
		assertThat(tarefa.getName()).isEqualTo("reconciliar-freemium");
		var meioDia = Instant.parse("2026-11-05T15:00:00Z"); // 12:00 em Sao Paulo (UTC-3)
		var proxima = tarefa.agenda().getInitialExecutionTime(meioDia);
		assertThat(proxima).isEqualTo(Instant.parse("2026-11-05T15:17:00Z")); // 12:17
		assertThat(tarefa.agenda().getInitialExecutionTime(Instant.parse("2026-11-05T15:30:00Z"))).isEqualTo(Instant.parse("2026-11-05T21:17:00Z")); // 18:17
	}

	@Test
	void quarentaEOitoHorasMaisUmSegundoExpiraEMenosUmSegundoFica() {
		var velho = pendente(Duration.ofHours(48).plusSeconds(1));
		var novo = pendente(Duration.ofHours(48).minusSeconds(1));
		assertThat(tarefa.executar(false)).isEqualTo("1 pedido(s) expirados");
		assertThat(status(velho)).isEqualTo(StatusPedido.CONFIRMACAO_EXPIRADA);
		assertThat(status(novo)).isEqualTo(StatusPedido.AGUARDANDO_CONFIRMACAO_EMAIL);
	}

	@Test
	void dryRunSoContaELoga(CapturedOutput log) {
		var velho = pendente(Duration.ofHours(60));
		assertThat(tarefa.executar(true)).isEqualTo("1 pedido(s) expirariam (simulação)");
		assertThat(status(velho)).isEqualTo(StatusPedido.AGUARDANDO_CONFIRMACAO_EMAIL);
		assertThat(log.getAll()).contains("reconciliar-freemium (dry-run): 1 pedido(s) expirariam");
	}

	@Test
	void umWarnAgregadoSemAlertaPorPedidoNemPii(CapturedOutput log) throws Exception {
		var a = pendente(Duration.ofHours(60));
		var b = pendente(Duration.ofHours(70));
		var email = jdbc.queryForObject("select email from pedido where id = ?", String.class, a);
		tarefa.executar(false);

		assertThat(status(a)).isEqualTo(StatusPedido.CONFIRMACAO_EXPIRADA);
		assertThat(status(b)).isEqualTo(StatusPedido.CONFIRMACAO_EXPIRADA);
		assertThat(log.getAll().lines().filter(l -> l.contains("WARN") && l.contains("reconciliar-freemium"))).hasSize(1);
		assertThat(log.getAll()).contains("2 pedido(s)").doesNotContain(email, a.toString());
		assertThat(jdbc.queryForObject("select count(*) from scheduled_tasks where task_name = 'alerta-admin'", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from scheduled_tasks", Integer.class)).isZero();
		assertThat(caixa.enviados()).isEmpty();
		assertThat(jdbc.queryForObject("select count(*) from pedido where status = 'ERRO'", Integer.class)).isZero();
	}

	@Test
	void semMudancaNaoHaWarn(CapturedOutput log) {
		pendente(Duration.ofHours(1));
		assertThat(tarefa.executar(false)).isEqualTo("nada a expirar");
		assertThat(log.getAll().lines().filter(l -> l.contains("WARN") && l.contains("reconciliar-freemium"))).isEmpty();
	}

	/** Carregado da revisao da T1: a linha do pedido e a trava; o fim e PAGO com link usado ou EXPIRADA, nunca misturado. */
	@Test
	void confirmacaoConcorrenteComAExpiracaoVenceOuPerdeSemEstadoMisto() throws Exception {
		int pago = 0;
		int expirado = 0;
		for (int rodada = 0; rodada < 12; rodada++) {
			var email = "frm-" + UUID.randomUUID() + "@exemplo.com";
			var id = pedidos.criarFreemium(FreemiumDeTeste.novo(email, "529.982.247-25"), "203.0.113.9");
			jdbc.update("update pedido set criado_em = ? where id = ?", OffsetDateTime.ofInstant(Instant.now().minus(Duration.ofHours(60)), ZoneOffset.UTC), id);
			var token = FreemiumDeTeste.link(jdbc, id, Instant.now().plus(Duration.ofHours(1)));
			var corte = Instant.now().minus(Duration.ofHours(48));

			var r = ConfirmacaoFreemiumTest.emParalelo(List.of(() -> saga.confirmar(token), () -> pedidos.expirarConfirmacoes(corte)));

			var status = status(id);
			var usado = jdbc.queryForObject("select usado_em is not null from freemium_confirmacao where pedido_id = ?", Boolean.class, id);
			var contas = jdbc.queryForObject("select count(*) from usuario where email = ?", Integer.class, email);
			var bloqueios = jdbc.queryForObject("select count(*) from freemium_bloqueio", Integer.class);
			var geracoes = jdbc.queryForObject("select count(*) from scheduled_tasks where task_name = 'gerar-oracao'", Integer.class);
			if (status == StatusPedido.PAGO) {
				pago++;
				assertThat(usado).isTrue();
				assertThat(List.of(contas, bloqueios, geracoes)).containsExactly(1, 1, 1);
				assertThat(r.get(0)).isInstanceOf(UUID.class);
			} else {
				expirado++;
				assertThat(status).isEqualTo(StatusPedido.CONFIRMACAO_EXPIRADA);
				assertThat(usado).isFalse();
				assertThat(List.of(contas, bloqueios, geracoes)).containsExactly(0, 0, 0);
				assertThat(r.get(0)).isInstanceOf(me.clama.infra.web.ErroDeNegocio.class);
			}
			limpar(); // uma rodada por vez: o bloqueio da anterior nao pode barrar a seguinte
		}
		assertThat(pago + expirado).isEqualTo(12);
	}
}
