package me.clama.pagamento.web;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import me.clama.BancoDeTeste;
import me.clama.conta.web.Navegador;
import me.clama.infra.cripto.HmacDeIdentificador;
import me.clama.infra.seguranca.LimiteDeTaxa;
import me.clama.pagamento.PagamentoApi;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** {@code POST /api/pedidos/{id}/pix} contra um WireMock no lugar do Mercado Pago (INV:90-93, D16, D17). */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(BancoDeTeste.class)
@ActiveProfiles("test") // base do MP em http (WireMock) so e aceita no perfil de teste
class PixTest {

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
	static void mercadoPago(DynamicPropertyRegistry r) {
		r.add("clama.pagamento.mercadopago-url", MP::baseUrl);
		r.add("clama.pagamento.mercadopago-token", () -> "test");
		r.add("clama.pagamento.webhook-url", () -> "https://api.clama.test/api/webhooks/mercadopago");
	}

	@LocalServerPort int porta;
	@Autowired JdbcTemplate jdbc;
	@Autowired LimiteDeTaxa limite;
	@Autowired PedidoRepository pedidos;
	@Autowired HmacDeIdentificador hmac;
	@Autowired PagamentoApi pagamento;
	@Autowired me.clama.pagamento.interno.Cobrancas cobrancas;
	@Autowired TransactionTemplate tx;
	@Autowired javax.sql.DataSource dataSource;

	@BeforeEach
	@AfterEach
	void limpar() { // banco compartilhado: nao deixa linha para os outros testes
		limite.limpar();
		MP.resetAll();
		jdbc.update("delete from cobranca");
		jdbc.update("delete from pedido");
		jdbc.update("delete from plano where tipo = 'COMUM'");
	}

	UUID pedido(String cpf) {
		var plano = jdbc.queryForObject("""
				insert into plano (nome, valor_centavos, complexidade, tipo, ordem) values (?, 2000, 'SIMPLES', 'COMUM', 1)
				returning id""", Long.class, "Pedido " + UUID.randomUUID());
		var agora = Instant.now();
		var d = new Pedido.Dados(null, plano, null, 3000, "Maria Souza", "maria@exemplo.com", cpf, null, null, null, "Pela família",
				agora, null, "1");
		return pedidos.saveAndFlush(Pedido.novo(d, hmac, agora)).getId();
	}

	UUID pedido() {
		return pedido("52998224725");
	}

	/** Resposta de Pix criado com outro id e o vencimento que o MP devolve. */
	static String pixCriado(long id, Instant expira) {
		return PIX_CRIADO.replace("1325814423", String.valueOf(id)).replace("\"status\": \"pending\",",
				"\"status\": \"pending\", \"date_of_expiration\": \"" + expira.atOffset(java.time.ZoneOffset.ofHours(-3)) + "\",");
	}

	List<Map<String, Object>> historico(UUID id) {
		return jdbc.queryForList("select n, mp_pagamento_id, cancelada_em is not null as cancelada from cobranca where pedido_id = ? order by n", id);
	}

	static void mpCria(int status, String corpo) {
		MP.stubFor(post("/v1/payments").willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(corpo)));
	}

	/** Navegador anonimo que ja pegou o cookie XSRF (como o front antes de um POST). */
	Navegador anonimo() throws Exception {
		var nav = new Navegador(porta);
		nav.get("/api/auth/eu");
		return nav;
	}

	HttpResponse<String> pix(UUID id) throws Exception {
		return anonimo().post("/api/pedidos/" + id + "/pix", "");
	}

	static JsonNode corpo(HttpResponse<String> r) {
		return Navegador.json(r);
	}

	static List<String> chavesEnviadas() {
		return MP.getAllServeEvents().reversed().stream().filter(e -> e.getRequest().getMethod().getName().equals("POST"))
				.map(e -> e.getRequest().getHeaders().getHeader("X-Idempotency-Key").firstValue()).toList();
	}

	@Test
	void criaACobrancaPedindo24HorasEDevolveOVencimentoDoMp() throws Exception {
		var doMp = Instant.now().plus(Duration.ofHours(23)).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
		mpCria(201, pixCriado(1325814423L, doMp));
		var id = pedido();

		var r = pix(id);

		assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
		var c = corpo(r);
		assertThat(c.get("pedidoId").asString()).isEqualTo(id.toString());
		assertThat(c.get("copiaECola").asString()).startsWith("00020126380014br.gov.bcb.pix");
		assertThat(c.get("qrBase64").asString()).startsWith("iVBORw0KGgo");
		assertThat(Instant.parse(c.get("expiraEm").asString())).isEqualTo(doMp);
		assertThat(chavesEnviadas()).containsExactly("clama-pedido-" + id);
		var pedido = MP.getAllServeEvents().getFirst().getRequest().getBodyAsString();
		var pedida = java.time.OffsetDateTime.parse(Navegador.JSON.readTree(pedido).get("date_of_expiration").asString()).toInstant();
		assertThat(Duration.between(Instant.now(), pedida)).isBetween(Duration.ofHours(23).plusMinutes(59), Duration.ofHours(24));
		MP.verify(postRequestedFor(urlEqualTo("/v1/payments")).withRequestBody(matchingJsonPath("$.external_reference", equalTo(id.toString()))));
		assertThat(jdbc.queryForMap("select n, mp_pagamento_id from cobranca where pedido_id = ?", id))
				.containsEntry("n", 1).containsEntry("mp_pagamento_id", 1325814423L);
	}

	@Test
	void segundaChamadaDevolveACobrancaVigenteSemChamarOMp() throws Exception {
		mpCria(201, PIX_CRIADO);
		var id = pedido();

		var a = pix(id);
		var b = pix(id);

		assertThat(b.statusCode()).isEqualTo(200);
		assertThat(corpo(b)).isEqualTo(corpo(a));
		MP.verify(1, postRequestedFor(urlEqualTo("/v1/payments")));
	}

	@Test
	void cobrancaVencidaViraOutraComChaveNumeradaEOHistoricoFica() throws Exception {
		mpCria(201, PIX_CRIADO);
		var id = pedido();
		pix(id);
		jdbc.update("update cobranca set expira_em = now() - interval '1 minute' where pedido_id = ?", id);
		mpCria(201, pixCriado(1325814999L, Instant.now().plus(Duration.ofHours(24))));

		var r = pix(id);

		assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
		assertThat(corpo(r).get("copiaECola").asString()).isNotNull();
		assertThat(chavesEnviadas()).containsExactly("clama-pedido-" + id, "clama-pedido-" + id + "-2");
		assertThat(historico(id)).extracting(m -> m.get("n"), m -> m.get("mp_pagamento_id")).containsExactly(
				org.assertj.core.groups.Tuple.tuple(1, 1325814423L), org.assertj.core.groups.Tuple.tuple(2, 1325814999L));
		jdbc.update("update cobranca set expira_em = now() - interval '1 minute' where pedido_id = ?", id);
		mpCria(201, pixCriado(1325815000L, Instant.now().plus(Duration.ofHours(24))));
		pix(id);
		assertThat(chavesEnviadas()).last().isEqualTo("clama-pedido-" + id + "-3");
		assertThat(historico(id)).hasSize(3);
	}

	/** O webhook (T5) reconhece qualquer pagamento do pedido, vigente ou nao; de outro pedido, nao. */
	@Test
	void pagamentosDoPedidoContinuamConsultaveis() throws Exception {
		mpCria(201, PIX_CRIADO);
		var id = pedido();
		pix(id);
		jdbc.update("update cobranca set expira_em = now() - interval '1 minute' where pedido_id = ?", id);
		mpCria(201, pixCriado(1325814999L, Instant.now().plus(Duration.ofHours(24))));
		pix(id);
		var outro = pedido();

		assertThat(cobrancas.ehCobrancaDoPedido(id, 1325814423L)).isTrue();
		assertThat(cobrancas.ehCobrancaDoPedido(id, 1325814999L)).isTrue();
		assertThat(cobrancas.ehCobrancaDoPedido(id, 42L)).isFalse();
		assertThat(cobrancas.ehCobrancaDoPedido(outro, 1325814423L)).isFalse();
	}

	@Test
	void pedidoQueNaoAguardaPagamentoDa409SemChamarOMp() throws Exception {
		var id = pedido();
		jdbc.update("update pedido set status = 'GERANDO_ORACAO' where id = ?", id);

		var r = pix(id);

		assertThat(r.statusCode()).isEqualTo(409);
		assertThat(corpo(r).get("codigo").asString()).isEqualTo("pedido_ja_pago");
		assertThat(corpo(r).get("mensagemPastoral").asString()).isEqualTo("Esse pedido já foi pago, vamos te encaminhar para a confirmação.");
		MP.verify(0, anyRequestedFor(anyUrl()));
	}

	@Test
	void semCpfDa422() throws Exception {
		var id = pedido(null);

		var r = pix(id);

		assertThat(r.statusCode()).isEqualTo(422);
		assertThat(corpo(r).get("mensagemPastoral").asString())
				.isEqualTo("Precisamos do seu CPF para gerar a cobrança. Volte ao formulário e complete o cadastro.");
		MP.verify(0, anyRequestedFor(anyUrl()));
	}

	@Test
	void valorAbaixoDoPisoDa422() throws Exception {
		var id = pedido();
		jdbc.update("update pedido set valor_centavos = 99 where id = ?", id);

		var r = pix(id);

		assertThat(r.statusCode()).isEqualTo(422);
		assertThat(corpo(r).get("mensagemPastoral").asString())
				.isEqualTo("O valor mínimo aceito pelo processador de pagamentos é R$ 1,00. Escolha um valor igual ou maior pra continuar.");
	}

	@Test
	void pedidoInexistenteOuIdMalformadoDa404() throws Exception {
		for (var id : List.of(UUID.randomUUID().toString(), "nao-e-uuid")) {
			var r = anonimo().post("/api/pedidos/" + id + "/pix", "");
			assertThat(r.statusCode()).as(id).isEqualTo(404);
			assertThat(corpo(r).get("mensagemPastoral").asString()).isEqualTo("Não encontramos esse pedido. Verifique se o link está correto.");
		}
	}

	@Test
	void anonimoSemCsrfDa403() throws Exception {
		mpCria(201, PIX_CRIADO);
		var r = new Navegador(porta).post("/api/pedidos/" + pedido() + "/pix", "");
		assertThat(r.statusCode()).isEqualTo(403);
		MP.verify(0, anyRequestedFor(anyUrl()));
	}

	@Test
	void duploCliqueCriaUmaCobrancaSo() throws Exception {
		MP.stubFor(post("/v1/payments").willReturn(okJson(PIX_CRIADO).withFixedDelay(800)));
		var id = pedido();
		var a = anonimo();
		var b = anonimo();
		var largada = new CountDownLatch(1);

		List<HttpResponse<String>> respostas;
		try (var threads = Executors.newFixedThreadPool(2)) {
			var fa = threads.submit(() -> {
				largada.await();
				return a.post("/api/pedidos/" + id + "/pix", "");
			});
			var fb = threads.submit(() -> {
				largada.await();
				return b.post("/api/pedidos/" + id + "/pix", "");
			});
			largada.countDown();
			respostas = List.of(fa.get(), fb.get());
		}

		MP.verify(1, postRequestedFor(urlEqualTo("/v1/payments")));
		assertThat(respostas).allSatisfy(r -> assertThat(r.statusCode()).as(r.body()).isEqualTo(200));
		assertThat(corpo(respostas.get(0))).isEqualTo(corpo(respostas.get(1)));
		assertThat(jdbc.queryForObject("select count(*) from cobranca", Integer.class)).isEqualTo(1);
	}

	/** Outra transacao segura a linha: espera o lock_timeout e responde 503 pastoral, sem chegar ao MP. */
	@Test
	void lockPresoDa503() throws Exception {
		var id = pedido();
		var preso = new CountDownLatch(1);
		var solta = new CountDownLatch(1);
		var segura = Thread.ofPlatform().start(() -> {
			try (var c = dataSource.getConnection()) {
				c.setAutoCommit(false);
				try (var st = c.prepareStatement("select 1 from pedido where id = ? for update")) {
					st.setObject(1, id);
					st.executeQuery();
				}
				preso.countDown();
				solta.await();
				c.rollback();
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
		preso.await();
		try {
			var t0 = System.nanoTime();
			var r = pix(id);
			var ms = (System.nanoTime() - t0) / 1_000_000;
			assertThat(r.statusCode()).as(r.body()).isEqualTo(503);
			assertThat(corpo(r).get("codigo").asString()).isEqualTo("pagamento_indisponivel");
			assertThat(ms).as("espera o lock_timeout de 15 s (PedidoRepository.travar)").isBetween(14_000L, 20_000L);
			MP.verify(0, anyRequestedFor(anyUrl()));
		} finally {
			solta.countDown();
			segura.join();
		}
	}

	@Test
	void erroDoMpNaoGravaNada() throws Exception {
		var id = pedido();
		mpCria(501, "{}");
		assertThat(pix(id).statusCode()).isEqualTo(503);
		mpCria(400, "{\"message\":\"x\",\"cause\":[{\"description\":\"Invalid user identification number\"}]}");
		var r = pix(id);
		assertThat(r.statusCode()).isEqualTo(422);
		assertThat(corpo(r).get("mensagemPastoral").asString()).isEqualTo("Tivemos um soluço com o pagamento. Pode tentar novamente em instantes.");
		mpCria(201, "{\"id\": 1325814425, \"status\": \"pending\"}");
		assertThat(pix(id).statusCode()).isEqualTo(503);
		assertThat(jdbc.queryForObject("select count(*) from cobranca", Integer.class)).isZero();
	}

	@Test
	void credencialRecusadaDa503SemRetentar() throws Exception { // o Sentry (mockStatic e por thread) esta no ErroDoMercadoPagoTest
		var id = pedido();
		mpCria(401, "{\"message\":\"Unauthorized access token\",\"cause\":[{\"code\":2002,\"description\":\"invalid access token\"}]}");
		var r = pix(id);
		assertThat(r.statusCode()).isEqualTo(503);
		assertThat(corpo(r).get("mensagemPastoral").asString()).isEqualTo("Tivemos um soluço processando seu pagamento. Já estamos olhando"
				+ " — tenta de novo em alguns instantes ou escreve pra contato@clama.me.");
		MP.verify(1, postRequestedFor(urlEqualTo("/v1/payments")));
	}

	@Test
	void limiteDe10PorMinutoPorIp() throws Exception {
		var id = pedido();
		jdbc.update("update pedido set status = 'PAGO' where id = ?", id); // 409 barato, sem MP
		var nav = anonimo();
		for (int i = 0; i < 10; i++) assertThat(nav.post("/api/pedidos/" + id + "/pix", "").statusCode()).isEqualTo(409);
		assertThat(nav.post("/api/pedidos/" + id + "/pix", "").statusCode()).isEqualTo(429);
	}

	@Test
	void cancelarCobrancaPendenteCancelaNoMpENuncaMaisEServida() throws Exception {
		mpCria(201, PIX_CRIADO);
		var id = pedido();
		pix(id);
		MP.stubFor(get(urlPathMatching("/v1/payments/\\d+")).willReturn(okJson("{\"id\": 1, \"status\": \"pending\"}")));
		MP.stubFor(put(urlPathMatching("/v1/payments/\\d+")).willReturn(okJson("{\"id\": 1, \"status\": \"cancelled\"}")));

		tx.executeWithoutResult(s -> pagamento.cancelarCobranca(id));

		MP.verify(putRequestedFor(urlPathEqualTo("/v1/payments/1325814423")).withRequestBody(equalToJson("{\"status\": \"cancelled\"}")));
		assertThat(historico(id)).singleElement().satisfies(m -> assertThat(m).containsEntry("cancelada", true));

		mpCria(201, pixCriado(1325814999L, Instant.now().plus(Duration.ofHours(24)))); // ainda vigente, mas cancelada
		var r = pix(id);
		assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
		assertThat(chavesEnviadas()).last().isEqualTo("clama-pedido-" + id + "-2");
		assertThat(historico(id)).extracting(m -> m.get("n"), m -> m.get("cancelada")).containsExactly(
				org.assertj.core.groups.Tuple.tuple(1, true), org.assertj.core.groups.Tuple.tuple(2, false));
		tx.executeWithoutResult(s -> pagamento.cancelarCobranca(id)); // cancela so a mais recente
		MP.verify(1, putRequestedFor(urlPathEqualTo("/v1/payments/1325814423")));
		MP.verify(1, putRequestedFor(urlPathEqualTo("/v1/payments/1325814999")));
		assertThat(historico(id)).extracting(m -> m.get("cancelada")).containsExactly(true, true);
	}

	@Test
	void cancelarCobrancaJaAprovadaDa409ENaoApaga() throws Exception {
		mpCria(201, PIX_CRIADO);
		var id = pedido();
		pix(id);
		MP.stubFor(get("/v1/payments/1325814423").willReturn(okJson("{\"id\": 1325814423, \"status\": \"approved\"}")));

		var e = org.assertj.core.api.Assertions.catchThrowableOfType(me.clama.infra.web.ErroDeNegocio.class,
				() -> tx.executeWithoutResult(s -> pagamento.cancelarCobranca(id)));

		assertThat(e.status().value()).isEqualTo(409);
		assertThat(e.mensagemPastoral()).isEqualTo("O pagamento já caiu; reprocesse o evento.");
		MP.verify(0, putRequestedFor(anyUrl()));
		assertThat(historico(id)).singleElement().satisfies(m -> assertThat(m).containsEntry("cancelada", false));
	}

	@Test
	void cancelarSemCobrancaNaoFazNada() {
		var id = pedido();
		tx.executeWithoutResult(s -> pagamento.cancelarCobranca(id));
		MP.verify(0, anyRequestedFor(anyUrl()));
	}
}
