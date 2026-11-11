package me.clama.oracao.web;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import me.clama.BancoDeTeste;
import me.clama.EmailDeTeste;
import me.clama.conta.interno.Papel;
import me.clama.conta.interno.UsuarioRepository;
import me.clama.conta.web.Navegador;
import me.clama.infra.cripto.HmacDeIdentificador;
import me.clama.pedido.PedidoApi;
import me.clama.pedido.interno.Pedido;
import me.clama.pedido.interno.PedidoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Gratuidade, reenvio e regeneracao do admin (D10). Sem a fila ligada: o que importa e a linha em {@code scheduled_tasks}
 * (enfileirada, antecipada ou ausente) e o estado do pedido. MP em WireMock.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import({ BancoDeTeste.class, EmailDeTeste.class })
@ActiveProfiles("test")
class AcoesDePedidoAdminTest {

	static final WireMockServer MP = new WireMockServer(WireMockConfiguration.options().dynamicPort());
	static final String PIX_CRIADO;

	static {
		MP.start();
		try {
			PIX_CRIADO = Files.readString(Path.of("src/test/resources/mercadopago/pix_criado.json"));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		r.add("clama.pagamento.mercadopago-url", MP::baseUrl);
		r.add("clama.pagamento.mercadopago-token", () -> "test");
		r.add("clama.pagamento.webhook-url", () -> "https://api.clama.test/api/webhooks/mercadopago");
	}

	@LocalServerPort int porta;
	@Autowired JdbcTemplate jdbc;
	@Autowired UsuarioRepository usuarios;
	@Autowired PasswordEncoder encoder;
	@Autowired PedidoRepository pedidos;
	@Autowired HmacDeIdentificador hmac;
	@Autowired PedidoApi api;

	@BeforeEach
	@AfterEach
	void limpar() {
		MP.resetAll();
		jdbc.update("delete from auditoria where entidade = 'pedido'");
		jdbc.update("delete from cobranca");
		jdbc.update(BancoDeTeste.LIMPAR_FILA);
		jdbc.update("delete from pedido");
		jdbc.update("delete from plano where tipo = 'COMUM'");
		jdbc.update("delete from usuario where email like 'adm-%@exemplo.com'");
	}

	// --- montagem ---

	/** AGUARDANDO_PAGAMENTO. */
	UUID pedido() {
		var plano = jdbc.queryForObject("""
				insert into plano (nome, valor_centavos, complexidade, tipo, ordem) values (?, 3000, 'SIMPLES', 'COMUM', 1)
				returning id""", Long.class, "Simples " + UUID.randomUUID());
		var agora = Instant.now();
		var d = new Pedido.Dados(null, plano, null, 3000, "Maria Souza", "maria@exemplo.com", "52998224725", null, (short) 40,
				"feminino", "Pela família", agora, null, "1");
		return pedidos.saveAndFlush(Pedido.novo(d, hmac, agora)).getId();
	}

	/** GERANDO_ORACAO, gratuito, sem job na fila. */
	UUID gerando() {
		var id = pedido();
		api.concederGratuidade(id);
		api.iniciarGeracao(id);
		jdbc.update(BancoDeTeste.LIMPAR_FILA);
		return id;
	}

	UUID comOracao() {
		var id = gerando();
		api.registrarOracao(id, "Senhor, acolhe a Maria.");
		return id;
	}

	UUID enviada() {
		var id = comOracao();
		api.marcarEnviado(id);
		return id;
	}

	UUID erroSemOracao() {
		var id = gerando();
		api.falhar(id, "credit_balance");
		return id;
	}

	String status(UUID id) {
		return jdbc.queryForObject("select status from pedido where id = ?", String.class, id);
	}

	int jobs(String tarefa, UUID id) {
		return jdbc.queryForObject("select count(*) from scheduled_tasks where task_name = ? and task_instance = ?", Integer.class,
				tarefa, tarefa + "/" + id);
	}

	int auditorias(UUID id, String acao) {
		return jdbc.queryForObject("select count(*) from auditoria where entidade = 'pedido' and entidade_id = ? and diff ->> 'acao' = ?",
				Integer.class, id.toString(), acao);
	}

	Navegador entrar(Papel papel) throws Exception {
		var email = "adm-" + UUID.randomUUID() + "@exemplo.com";
		Navegador.usuario(usuarios, encoder, email, "senha-forte-123", papel);
		var nav = new Navegador(porta);
		assertThat(nav.login(email, "senha-forte-123").statusCode()).isEqualTo(200);
		return nav;
	}

	static String url(UUID id, String acao) {
		return "/api/admin/pedidos/" + id + "/" + acao;
	}

	void cobrar(UUID id) throws Exception {
		MP.stubFor(post("/v1/payments").willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody(PIX_CRIADO)));
		var anonimo = new Navegador(porta);
		anonimo.get("/api/auth/eu");
		assertThat(anonimo.post("/api/pedidos/" + id + "/pix", "").statusCode()).isEqualTo(200);
	}

	/** Deixa um job pendente do pedido: reaproveita a linha do reenvio que o ouvinte enfileira (so a existencia importa). */
	void pendente(UUID id, String tarefa) {
		api.solicitarReenvio(id);
		jdbc.update("update scheduled_tasks set task_name = ?, task_instance = ? where task_name = 'reenviar-oracao' and task_instance = ?",
				tarefa, tarefa + "/" + id, "reenviar-oracao/" + id);
		assertThat(jobs(tarefa, id)).isEqualTo(1);
	}

	// --- seguranca ---

	@Test
	void anonimoRecebe401EClienteRecebe403() throws Exception {
		var id = pedido();
		var anonimo = new Navegador(porta);
		anonimo.get("/api/auth/eu"); // pega o cookie XSRF: sem ele o 403 viria do CSRF
		for (var acao : new String[] { "gratuidade", "reenvio", "regeneracao" }) {
			assertThat(anonimo.post(url(id, acao), "").statusCode()).as(acao).isEqualTo(401);
			assertThat(entrar(Papel.CLIENTE).post(url(id, acao), "").statusCode()).as(acao).isEqualTo(403);
		}
	}

	@Test
	void pedidoInexistenteDa404() throws Exception {
		var adm = entrar(Papel.ADMIN);
		for (var acao : new String[] { "gratuidade", "reenvio", "regeneracao" }) {
			assertThat(adm.post(url(UUID.randomUUID(), acao), "").statusCode()).as(acao).isEqualTo(404);
		}
		assertThat(adm.post("/api/admin/pedidos/xyz/reenvio", "").statusCode()).isEqualTo(404);
	}

	// --- gratuidade ---

	@Test
	void gratuidadeCancelaOPixConcedeEnfileiraAGeracaoEAudita() throws Exception {
		var id = pedido();
		cobrar(id);
		MP.stubFor(get(urlPathMatching("/v1/payments/\\d+")).willReturn(okJson("{\"id\": 1, \"status\": \"pending\"}")));
		MP.stubFor(put(urlPathMatching("/v1/payments/\\d+")).willReturn(okJson("{\"id\": 1, \"status\": \"cancelled\"}")));

		var res = entrar(Papel.ADMIN).post(url(id, "gratuidade"), "");

		assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
		var corpo = Navegador.json(res);
		assertThat(corpo.get("status").asString()).isEqualTo("PAGO");
		assertThat(corpo.get("gratuito").asBoolean()).isTrue();
		assertThat(corpo.get("valorCentavos").asLong()).isZero();
		assertThat(status(id)).isEqualTo("PAGO");
		MP.verify(1, putRequestedFor(urlPathEqualTo("/v1/payments/1325814423")));
		assertThat(jdbc.queryForObject("select cancelada_em is not null from cobranca where pedido_id = ?", Boolean.class, id)).isTrue();
		assertThat(jobs("gerar-oracao", id)).isEqualTo(1);
		assertThat(auditorias(id, "gratuidade")).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from repasse", Integer.class)).isZero();
	}

	@Test
	void gratuidadeSemCobrancaFunciona() throws Exception {
		var id = pedido();
		assertThat(entrar(Papel.ADMIN).post(url(id, "gratuidade"), "").statusCode()).isEqualTo(200);
		assertThat(status(id)).isEqualTo("PAGO");
		MP.verify(0, anyRequestedFor(anyUrl()));
	}

	@Test
	void gratuidadeComPagamentoJaAprovadoDa409ENadaMuda() throws Exception {
		var id = pedido();
		cobrar(id);
		MP.stubFor(get(urlPathMatching("/v1/payments/\\d+")).willReturn(okJson("{\"id\": 1325814423, \"status\": \"approved\"}")));

		var res = entrar(Papel.ADMIN).post(url(id, "gratuidade"), "");

		assertThat(res.statusCode()).isEqualTo(409);
		var corpo = Navegador.json(res);
		assertThat(corpo.get("codigo").asString()).isEqualTo("pagamento_ja_aprovado");
		assertThat(corpo.get("mensagemPastoral").asString()).isEqualTo("O pagamento já caiu; reprocesse o evento.");
		assertThat(status(id)).isEqualTo("AGUARDANDO_PAGAMENTO");
		assertThat(jdbc.queryForObject("select gratuito from pedido where id = ?", Boolean.class, id)).isFalse();
		assertThat(jdbc.queryForObject("select cancelada_em is null from cobranca where pedido_id = ?", Boolean.class, id)).isTrue();
		MP.verify(0, putRequestedFor(anyUrl()));
		assertThat(jobs("gerar-oracao", id)).isZero();
		assertThat(auditorias(id, "gratuidade")).isZero();
	}

	@Test
	void gratuidadeForaDeAguardandoPagamentoDa409SemChamarOMp() throws Exception {
		var id = comOracao();
		var res = entrar(Papel.ADMIN).post(url(id, "gratuidade"), "");
		assertThat(res.statusCode()).isEqualTo(409);
		assertThat(Navegador.json(res).get("mensagemPastoral").asString()).contains("aguarda o pagamento");
		assertThat(status(id)).isEqualTo("ORACAO_GERADA");
		MP.verify(0, anyRequestedFor(anyUrl()));
	}

	// --- reenvio ---

	@Test
	void reenvioDeOracaoGeradaEnfileiraEAudita() throws Exception {
		var id = comOracao();
		var res = entrar(Papel.ADMIN).post(url(id, "reenvio"), "");
		assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
		assertThat(Navegador.json(res).get("jaEmAndamento").asBoolean()).isFalse();
		assertThat(status(id)).isEqualTo("ORACAO_GERADA");
		assertThat(jobs("reenviar-oracao", id)).isEqualTo(1);
		assertThat(auditorias(id, "reenvio")).isEqualTo(1);
	}

	@Test
	void reenvioDeEnviadaGratuitaContinuaEnviada() throws Exception {
		var id = enviada();
		var res = entrar(Papel.ADMIN).post(url(id, "reenvio"), "");
		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(Navegador.json(res).get("status").asString()).isEqualTo("ENVIADA");
		assertThat(Navegador.json(res).get("gratuito").asBoolean()).isTrue();
		assertThat(status(id)).isEqualTo("ENVIADA");
		assertThat(jobs("reenviar-oracao", id)).isEqualTo(1);
	}

	@Test
	void reenvioDeErroComOracaoVoltaAOracaoGerada() throws Exception {
		var id = comOracao();
		api.falhar(id, "falha_envio");
		var res = entrar(Papel.ADMIN).post(url(id, "reenvio"), "");
		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(status(id)).isEqualTo("ORACAO_GERADA");
		assertThat(jobs("reenviar-oracao", id)).isEqualTo(1);
	}

	@Test
	void reenvioSemOracaoDa409() throws Exception {
		var adm = entrar(Papel.ADMIN);
		for (var id : new UUID[] { pedido(), gerando(), erroSemOracao() }) {
			var antes = status(id);
			var res = adm.post(url(id, "reenvio"), "");
			assertThat(res.statusCode()).as(antes).isEqualTo(409);
			assertThat(Navegador.json(res).get("mensagemPastoral").asString()).contains("oração pronta");
			assertThat(status(id)).isEqualTo(antes);
			assertThat(jobs("reenviar-oracao", id)).isZero();
		}
	}

	@Test
	void reenvioComGeracaoOuEnvioNaFilaENoOp() throws Exception {
		var adm = entrar(Papel.ADMIN);
		for (var tarefa : new String[] { "gerar-oracao", "enviar-oracao" }) {
			var id = comOracao();
			pendente(id, tarefa);
			var res = adm.post(url(id, "reenvio"), "");
			assertThat(res.statusCode()).as(tarefa).isEqualTo(200);
			assertThat(Navegador.json(res).get("jaEmAndamento").asBoolean()).as(tarefa).isTrue();
			assertThat(jobs("reenviar-oracao", id)).as(tarefa).isZero();
			assertThat(status(id)).isEqualTo("ORACAO_GERADA");
			assertThat(auditorias(id, "reenvio")).isZero();
		}
	}

	// --- regeneracao ---

	@Test
	void regeneracaoDeErroSemOracaoVoltaAPagoZeraEEnfileira() throws Exception {
		var id = erroSemOracao();
		jdbc.update("update pedido set tentativas = 3 where id = ?", id);
		var res = entrar(Papel.ADMIN).post(url(id, "regeneracao"), "");
		assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
		var corpo = Navegador.json(res);
		assertThat(corpo.get("status").asString()).isEqualTo("PAGO");
		assertThat(corpo.get("ultimoErro").isNull()).isTrue();
		assertThat(status(id)).isEqualTo("PAGO");
		assertThat(jdbc.queryForObject("select tentativas from pedido where id = ?", Integer.class, id)).isZero();
		assertThat(jdbc.queryForObject("select ultimo_erro from pedido where id = ?", String.class, id)).isNull();
		assertThat(jobs("gerar-oracao", id)).isEqualTo(1);
		assertThat(auditorias(id, "regeneracao")).isEqualTo(1);
	}

	@Test
	void regeneracaoAntecipaOJobDeGeracaoJaNaFila() throws Exception {
		var id = pedido();
		api.concederGratuidade(id); // gerar-oracao/<id> na fila
		api.iniciarGeracao(id);
		api.falhar(id, "pedido_preso");
		jdbc.update("update scheduled_tasks set execution_time = now() + interval '1 hour', consecutive_failures = 2");

		var res = entrar(Papel.ADMIN).post(url(id, "regeneracao"), "");

		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(jobs("gerar-oracao", id)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select execution_time < now() + interval '1 minute' from scheduled_tasks", Boolean.class)).isTrue();
		assertThat(jdbc.queryForObject("select consecutive_failures from scheduled_tasks", Integer.class)).isZero();
	}

	@Test
	void regeneracaoForaDeErroSemOracaoDa409() throws Exception {
		var adm = entrar(Papel.ADMIN);
		var erroComOracao = comOracao();
		api.falhar(erroComOracao, "falha_envio");
		for (var id : new UUID[] { pedido(), gerando(), comOracao(), enviada(), erroComOracao }) {
			var antes = status(id);
			var res = adm.post(url(id, "regeneracao"), "");
			assertThat(res.statusCode()).as(antes).isEqualTo(409);
			assertThat(status(id)).isEqualTo(antes);
			assertThat(jobs("gerar-oracao", id)).isZero();
		}
	}
}
