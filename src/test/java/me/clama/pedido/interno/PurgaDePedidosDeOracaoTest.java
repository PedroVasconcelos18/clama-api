package me.clama.pedido.interno;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import me.clama.BancoDeTeste;
import me.clama.conta.interno.Papel;
import me.clama.conta.interno.UsuarioRepository;
import me.clama.conta.web.Navegador;
import me.clama.infra.cripto.HmacDeIdentificador;
import me.clama.infra.seguranca.LimiteDeTaxa;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** {@code purgar-pedidos-de-oracao} (D15, pergunta 1): o texto do pedido some 30 dias apos o envio; a oracao fica. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(BancoDeTeste.class)
class PurgaDePedidosDeOracaoTest {

	static final String TEXTO = "Pela minha mae Joana";
	static final String ORACAO = "Senhor, acolhe a Joana.";

	@LocalServerPort int porta;
	@Autowired PurgaDePedidosDeOracao tarefa;
	@Autowired PedidoRepository pedidos;
	@Autowired JdbcTemplate jdbc;
	@Autowired UsuarioRepository usuarios;
	@Autowired PasswordEncoder encoder;
	@Autowired HmacDeIdentificador hmac;
	@Autowired LimiteDeTaxa limite;

	Long plano;

	@BeforeEach
	@AfterEach
	void limpar() {
		limite.limpar();
		jdbc.update(BancoDeTeste.LIMPAR_FILA);
		jdbc.update("delete from pedido");
		jdbc.update("delete from plano where tipo = 'COMUM'");
		jdbc.update("delete from usuario where email like 'ppo-%'");
	}

	@BeforeEach
	void plano() {
		plano = jdbc.queryForObject("insert into plano (nome, valor_centavos, complexidade, tipo, ordem) values ('Simples', 3000, 'SIMPLES', 'COMUM', 1) returning id", Long.class);
	}

	/** Pedido ENVIADA; {@code enviadaHa} atras. */
	UUID enviado(Long usuario, Duration enviadaHa) {
		var agora = Instant.now();
		var d = new Pedido.Dados(usuario, plano, null, 3000, "Maria Souza", "ppo-" + UUID.randomUUID() + "@exemplo.com", "52998224725", null,
				(short) 40, "F", TEXTO, agora, null, "1", null);
		var p = pedidos.saveAndFlush(Pedido.novo(d, hmac, agora));
		p.confirmarPagamento(agora);
		p.iniciarGeracao(agora);
		p.registrarOracao(ORACAO, agora);
		p.marcarEnviado(agora);
		pedidos.saveAndFlush(p);
		jdbc.update("update pedido set enviada_em = ? where id = ?", OffsetDateTime.ofInstant(Instant.now().minus(enviadaHa), ZoneOffset.UTC), p.getId());
		return p.getId();
	}

	Pedido pedido(UUID id) {
		return pedidos.findById(id).orElseThrow();
	}

	@Test
	void nomeECron() {
		assertThat(tarefa.getName()).isEqualTo("purgar-pedidos-de-oracao");
		assertThat(tarefa.cron()).isEqualTo("0 15 3 * * *");
		assertThat(tarefa.agenda().getInitialExecutionTime(Instant.parse("2026-11-05T00:00:00Z"))).isEqualTo(Instant.parse("2026-11-05T06:15:00Z")); // 03:15 em SP
	}

	@Test
	void trintaDiasMaisUmSegundoZeraOTextoEAOracaoFica() throws Exception {
		var email = "ppo-" + Navegador.emailUnico();
		var conta = Navegador.usuario(usuarios, encoder, email, "senha-forte-123", Papel.CLIENTE);
		var velho = enviado(conta.getId(), Duration.ofDays(30).plusSeconds(1));
		var recente = enviado(conta.getId(), Duration.ofDays(30).minusSeconds(1));
		var semEnvio = pedidos.saveAndFlush(Pedido.novo(new Pedido.Dados(conta.getId(), plano, null, 3000, "Ana", "ana@exemplo.com", "52998224725",
				null, (short) 30, "F", TEXTO, Instant.now(), null, "1", null), hmac, Instant.now())).getId();

		assertThat(tarefa.executar(false)).isEqualTo("1 texto(s) de pedido apagados");

		assertThat(pedido(velho).getPedidoOracao()).isNull();
		assertThat(pedido(velho).getOracao()).isEqualTo(ORACAO);
		assertThat(pedido(recente).getPedidoOracao()).isEqualTo(TEXTO);
		assertThat(pedido(semEnvio).getPedidoOracao()).isEqualTo(TEXTO);
		assertThat(tarefa.executar(false)).isEqualTo("nada a apagar");

		// "Minha conta" ainda mostra a oracao do pedido purgado
		var nav = new Navegador(porta);
		assertThat(nav.login(conta.getEmail(), "senha-forte-123").statusCode()).isEqualTo(200);
		var lista = Navegador.json(nav.get("/api/conta/pedidos"));
		boolean achou = false;
		for (var item : lista) {
			if (item.get("id").asString().equals(velho.toString())) {
				achou = true;
				assertThat(item.get("oracaoGerada").asString()).isEqualTo(ORACAO);
			}
		}
		assertThat(achou).isTrue();
	}

	@Test
	void dryRunSoConta() {
		var velho = enviado(null, Duration.ofDays(40));
		assertThat(tarefa.executar(true)).isEqualTo("1 texto(s) de pedido seriam apagados (simulação)");
		assertThat(pedido(velho).getPedidoOracao()).isEqualTo(TEXTO);
	}

	/** Pergunta 1 do mapa: a purga e irreversivel, entao vai desligada ate o Pedro confirmar. */
	@Test
	void aConfiguracaoPadraoVaiEmDryRun() {
		assertThat(tarefa.simulacaoConfigurada()).isTrue();
	}
}
