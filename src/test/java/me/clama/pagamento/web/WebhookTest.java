package me.clama.pagamento.web;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import me.clama.BancoDeTeste;
import me.clama.CaixaDeSaidaEmMemoria;
import me.clama.EmailDeTeste;
import me.clama.conta.web.Navegador;
import me.clama.infra.cripto.HmacDeIdentificador;
import me.clama.infra.email.EnvioAssincrono;
import me.clama.pagamento.interno.Cobrancas;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import tools.jackson.databind.JsonNode;

/**
 * {@code POST /api/webhooks/mercadopago} contra um WireMock no lugar do Mercado Pago: assinatura, todos os ramos do
 * INV:94-99, D8 (valor, moeda, orfao), D9 (estorno x repasse), D11 (alerta sem PII) e concorrencia.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import({ BancoDeTeste.class, EmailDeTeste.class })
@ActiveProfiles("test") // base do MP em http (WireMock) so e aceita no perfil de teste
class WebhookTest {

	static final WireMockServer MP = new WireMockServer(WireMockConfiguration.options().dynamicPort());
	static final String SEGREDO = "segredo-do-webhook-de-teste";
	static final long PAG = 1325814423L;
	static final String ADMIN = "contato@clama.me";
	static final String PASTORAL_ASSINATURA = "Não pudemos confirmar quem enviou essa requisição.";

	static {
		MP.start();
	}

	@DynamicPropertySource
	static void mercadoPago(DynamicPropertyRegistry r) {
		r.add("clama.pagamento.mercadopago-url", MP::baseUrl);
		r.add("clama.pagamento.mercadopago-token", () -> "test");
		r.add("clama.pagamento.webhook-segredo", () -> SEGREDO);
	}

	@LocalServerPort int porta;
	@Autowired JdbcTemplate jdbc;
	@Autowired PedidoRepository pedidos;
	@Autowired HmacDeIdentificador hmac;
	@Autowired CaixaDeSaidaEmMemoria caixa;
	@Autowired EnvioAssincrono envio;
	@MockitoSpyBean Cobrancas cobrancas;

	final HttpClient http = HttpClient.newHttpClient();

	@BeforeEach
	@AfterEach
	void limpar() throws Exception {
		for (int i = 0; i < 500 && envio.pendentes() > 0; i++) Thread.sleep(10); // o link de senha grava token
		MP.resetAll();
		caixa.limpar();
		jdbc.update("delete from pagamento_evento");
		jdbc.update("delete from repasse");
		jdbc.update("delete from cobranca");
		jdbc.update(me.clama.BancoDeTeste.LIMPAR_FILA); // GeracaoSolicitada enfileira gerar-oracao e comita
		jdbc.update("delete from pedido");
		jdbc.update("delete from usuario where email like '%@exemplo.com'");
		jdbc.update("delete from plano where tipo = 'COMUM'");
		jdbc.update("delete from instituicao");
	}

	// --- montagem ---

	Long instituicao() {
		return jdbc.queryForObject("insert into instituicao (nome) values (?) returning id", Long.class,
				"Lar " + UUID.randomUUID());
	}

	UUID pedido(long valor, Long instituicao, String email) {
		var plano = jdbc.queryForObject("""
				insert into plano (nome, valor_centavos, complexidade, tipo, ordem) values (?, 2000, 'SIMPLES', 'COMUM', 1)
				returning id""", Long.class, "Pedido de Oração " + UUID.randomUUID());
		var agora = Instant.now();
		var d = new Pedido.Dados(null, plano, instituicao, valor, "Maria Souza", email, "52998224725", null, null, null,
				"Pela família", agora, null, "1");
		return pedidos.saveAndFlush(Pedido.novo(d, hmac, agora)).getId();
	}

	UUID pedido() {
		return pedido(3001, instituicao(), "maria@exemplo.com");
	}

	void cobranca(UUID pedido, int n, long mpId) {
		jdbc.update("""
				insert into cobranca (pedido_id, n, mp_pagamento_id, pix_copia_e_cola, expira_em, criada_em)
				values (?, ?, ?, 'qr', now() + interval '1 day', now())""", pedido, n, mpId);
	}

	static void mp(long id, String status, Object referencia, String valor, String moeda) {
		mp(id, status, referencia, valor, moeda, "accredited");
	}

	static void mp(long id, String status, Object referencia, String valor, String moeda, String detalhe) {
		var ref = referencia == null ? "null" : "\"" + referencia + "\"";
		MP.stubFor(get("/v1/payments/" + id).willReturn(okJson("""
				{"id": %d, "status": "%s", "status_detail": "%s", "external_reference": %s, "transaction_amount": %s, "currency_id": "%s"}"""
				.formatted(id, status, detalhe, ref, valor, moeda))));
	}

	static void aprovado(long id, UUID pedido) {
		mp(id, "approved", pedido, "30.01", "BRL");
	}

	static String assinar(String dataId, String requestId, String ts) throws Exception {
		var mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(SEGREDO.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return HexFormat.of().formatHex(mac.doFinal(("id:" + dataId + ";request-id:" + requestId + ";ts:" + ts + ";")
				.getBytes(StandardCharsets.UTF_8)));
	}

	HttpResponse<String> enviar(String query, String corpo, String... headers) throws Exception {
		return enviarEm("/api/webhooks/mercadopago", query, corpo, headers);
	}

	HttpResponse<String> enviarEm(String caminho, String query, String corpo, String... headers) throws Exception {
		var b = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho + query))
				.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(corpo));
		if (headers.length > 0) b.headers(headers);
		return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	/** Notificacao assinada como o MP manda: data.id na query (o assinado) e no corpo. */
	HttpResponse<String> notificar(long idNotificacao, long dataId, String tipo) throws Exception {
		var requestId = "req-" + idNotificacao;
		var ts = "1762434000";
		var corpo = "{\"id\": " + idNotificacao + ", \"type\": \"" + tipo + "\", \"action\": \"payment.updated\", "
				+ "\"api_version\": \"v1\", \"live_mode\": true, \"user_id\": \"2061717470\", "
				+ "\"date_created\": \"2026-11-06T13:00:00Z\", \"data\": {\"id\": \"" + dataId + "\", \"extra\": 1}}";
		return enviar("?data.id=" + dataId + "&type=" + tipo, corpo, "x-request-id", requestId, "x-signature",
				"ts=" + ts + ",v1=" + assinar(String.valueOf(dataId), requestId, ts));
	}

	HttpResponse<String> notificar(long idNotificacao) throws Exception {
		return notificar(idNotificacao, PAG, "payment");
	}

	static String status(HttpResponse<String> r) {
		assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
		return Navegador.json(r).get("status").asString();
	}

	String evento(long idNotificacao) {
		return jdbc.queryForObject("select status || ' ' || coalesce(motivo, '-') from pagamento_evento where id_externo = ?",
				String.class, String.valueOf(idNotificacao));
	}

	String statusDoPedido(UUID id) {
		return jdbc.queryForObject("select status from pedido where id = ?", String.class, id);
	}

	int contar(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	int getsNoMp() {
		return MP.findAll(getRequestedFor(urlPathMatching("/v1/payments/.*"))).size();
	}

	/** O alerta ao admin (D11): id, status, motivo, plano, valor e link; nada de PII do pedido. */
	String alerta(UUID pedido) {
		var enviados = caixa.aguardarPara(ADMIN, 1);
		assertThat(enviados).hasSize(1);
		var email = enviados.getFirst().email();
		assertThat(email.texto() + email.html() + email.assunto()).doesNotContain("Maria", "maria@", "52998224725",
				"Pela família");
		assertThat(email.texto()).contains(pedido.toString(), "/admin/pedidos?id=" + pedido);
		return email.texto();
	}

	// --- assinatura e corpo ---

	@Test
	void semAssinaturaOuComAssinaturaInvalidaE401SemTrabalho() throws Exception {
		var corpo = "{\"id\": 1, \"type\": \"payment\", \"data\": {\"id\": \"" + PAG + "\"}}";
		var semNada = enviar("?data.id=" + PAG, corpo);
		var errada = enviar("?data.id=" + PAG, corpo, "x-request-id", "r", "x-signature", "ts=1,v1=" + "0".repeat(64));
		var semDataId = enviar("?type=payment", corpo, "x-request-id", "r", "x-signature",
				"ts=1,v1=" + assinar(String.valueOf(PAG), "r", "1"));
		var outroSegredo = enviar("?data.id=" + PAG, corpo, "x-request-id", "r", "x-signature",
				"ts=1,v1=" + assinar(String.valueOf(PAG + 1), "r", "1"));

		for (var r : List.of(semNada, errada, semDataId, outroSegredo)) {
			assertThat(r.statusCode()).as(r.body()).isEqualTo(401);
			assertThat(Navegador.json(r).get("codigo").asString()).isEqualTo("assinatura_invalida");
			assertThat(Navegador.json(r).get("mensagemPastoral").asString()).isEqualTo(PASTORAL_ASSINATURA);
		}
		assertThat(contar("select count(*) from pagamento_evento")).isZero();
		assertThat(getsNoMp()).isZero();
	}

	@Test
	void barraFinalDaUrlAntigaDoPainelTambemFunciona() throws Exception {
		var corpo = "{\"id\": 1, \"type\": \"merchant_order\", \"data\": {\"id\": \"" + PAG + "\"}}";
		var semAssinatura = enviarEm("/api/webhooks/mercadopago/", "?data.id=" + PAG, corpo);
		assertThat(semAssinatura.statusCode()).as(semAssinatura.body()).isEqualTo(401); // chegou na cadeia do webhook
		assertThat(Navegador.json(semAssinatura).get("codigo").asString()).isEqualTo("assinatura_invalida");

		var ts = "1762434000";
		var assinado = enviarEm("/api/webhooks/mercadopago/", "?data.id=" + PAG, corpo, "x-request-id", "r-barra", "x-signature",
				"ts=" + ts + ",v1=" + assinar(String.valueOf(PAG), "r-barra", ts));
		assertThat(assinado.statusCode()).as(assinado.body()).isEqualTo(200);
		assertThat(status(assinado)).isEqualTo("ignored");
	}

	@Test
	void corpoAcimaDoTetoE400AntesDaAssinatura() throws Exception {
		var grande = "{\"id\": 1, \"x\": \"" + "a".repeat(8_200) + "\"}"; // teto de 8 KB
		var r = enviar("?data.id=" + PAG, grande);
		assertThat(r.statusCode()).as("400 antes do 401 da assinatura").isEqualTo(400);
		assertThat(Navegador.json(r).get("codigo").asString()).isEqualTo("corpo_grande_demais");
		assertThat(contar("select count(*) from pagamento_evento")).isZero();
	}

	@Test
	void corpoSemIdOuQueNaoEObjetoE400() throws Exception {
		var ts = "1762434000";
		var assinado = new String[] { "x-request-id", "r", "x-signature", "ts=" + ts + ",v1=" + assinar(String.valueOf(PAG), "r", ts) };
		for (var corpo : List.of("{\"type\": \"payment\"}", "nao e json", "[1]", "", "{\"id\": null}",
				"{\"id\": \"" + "9".repeat(65) + "\"}")) {
			var r = enviar("?data.id=" + PAG, corpo, assinado);
			assertThat(r.statusCode()).as(corpo).isEqualTo(400);
			assertThat(Navegador.json(r).get("codigo").asString()).isEqualTo("payload_invalido");
		}
		assertThat(contar("select count(*) from pagamento_evento")).isZero();
	}

	@Test
	void tipoQueNaoEPaymentFicaIgnoradoSemBuscarNoMp() throws Exception {
		assertThat(status(notificar(1, PAG, "merchant_order"))).isEqualTo("ignored");
		assertThat(evento(1)).isEqualTo("IGNORADO tipo_nao_payment");
		assertThat(getsNoMp()).isZero();
	}

	// --- aprovacao ---

	@Test
	void aprovadoConfirmaVinculaContaERegistraRepasse() throws Exception {
		var inst = instituicao();
		var id = pedido(3001, inst, "maria@exemplo.com");
		cobranca(id, 1, PAG);
		aprovado(PAG, id);

		var r = notificar(1);

		assertThat(status(r)).isEqualTo("ok");
		assertThat(r.headers().firstValue("Set-Cookie")).as("cadeia sem sessao").isEmpty();
		assertThat(statusDoPedido(id)).isEqualTo("PAGO");
		var usuario = jdbc.queryForObject("select usuario_id from pedido where id = ?", Long.class, id);
		assertThat(jdbc.queryForObject("select email from usuario where id = ?", String.class, usuario))
				.isEqualTo("maria@exemplo.com");
		var repasse = jdbc.queryForMap("select * from repasse where pedido_id = ?", id);
		assertThat(repasse).containsEntry("instituicao_id", inst).containsEntry("valor_base_centavos", 3001L)
				.containsEntry("percentual", 20).containsEntry("valor_centavos", 600L)
				.containsEntry("status", "REGISTRADO");
		assertThat(evento(1)).startsWith("PROCESSADO");
		assertThat(jdbc.queryForMap("select pedido_id, mp_pagamento_id from pagamento_evento where id_externo = '1'"))
				.containsEntry("pedido_id", id).containsEntry("mp_pagamento_id", String.valueOf(PAG));
		assertThat(Navegador.JSON.readTree(jdbc.queryForObject("select payload::text from pagamento_evento where id_externo = '1'",
				String.class))).as("so id, type, action e data.id").isEqualTo(Navegador.JSON.readTree("""
						{"id": 1, "type": "payment", "action": "payment.updated", "data": {"id": "%d"}}""".formatted(PAG)));
		assertThat(contar("select count(*) from cobranca where pedido_id = ? and paga_em is not null", id)).isEqualTo(1);
		assertThat(caixa.aguardarPara("maria@exemplo.com", 1)).as("link de definir senha da conta nova").hasSize(1);
		assertThat(caixa.para(ADMIN)).isEmpty();

		// reentrega do mesmo id: nada de novo, nem GET
		assertThat(status(notificar(1))).isEqualTo("already_processed");
		assertThat(getsNoMp()).isEqualTo(1);
		// outra notificacao do mesmo pagamento (payment.created + payment.updated)
		assertThat(status(notificar(2))).isEqualTo("already_paid");
		assertThat(evento(2)).isEqualTo("IGNORADO ja_pago");
		assertThat(contar("select count(*) from repasse")).isEqualTo(1);
		assertThat(contar("select count(*) from usuario where email = 'maria@exemplo.com'")).isEqualTo(1);
	}

	@Test
	void pedidoDeQuemTemContaNaoCriaOutra() throws Exception {
		var id = pedido();
		var dono = jdbc.queryForObject("""
				insert into usuario (email, email_canonico, nome_completo, papel, ativo, criado_em)
				values ('dona@exemplo.com', 'dona@exemplo.com', 'Dona', 'CLIENTE', true, now()) returning id""", Long.class);
		jdbc.update("update pedido set usuario_id = ? where id = ?", dono, id);
		cobranca(id, 1, PAG);
		aprovado(PAG, id);

		assertThat(status(notificar(1))).isEqualTo("ok");
		assertThat(jdbc.queryForObject("select usuario_id from pedido where id = ?", Long.class, id)).isEqualTo(dono);
		assertThat(contar("select count(*) from usuario where email = 'maria@exemplo.com'")).isZero();
	}

	@Test
	void semInstituicaoOuComRepasseZeroNaoRegistraRepasse() throws Exception {
		var semInst = pedido(3001, null, "maria@exemplo.com");
		cobranca(semInst, 1, PAG);
		aprovado(PAG, semInst);
		assertThat(status(notificar(1))).isEqualTo("ok");

		var centavos = pedido(4, instituicao(), "joana@exemplo.com"); // 20% de 4 = 0
		cobranca(centavos, 1, PAG + 1);
		mp(PAG + 1, "approved", centavos, "0.04", "BRL");
		assertThat(status(notificar(2, PAG + 1, "payment"))).isEqualTo("ok");

		assertThat(statusDoPedido(semInst)).isEqualTo("PAGO");
		assertThat(statusDoPedido(centavos)).isEqualTo("PAGO");
		assertThat(contar("select count(*) from repasse")).isZero();
	}

	@Test
	void valorOuMoedaDivergenteNaoPagaEAlerta() throws Exception {
		var id = pedido();
		cobranca(id, 1, PAG);
		mp(PAG, "approved", id, "30.00", "BRL");

		assertThat(status(notificar(1))).isEqualTo("ignored");
		assertThat(evento(1)).isEqualTo("IGNORADO valor_divergente");
		assertThat(statusDoPedido(id)).isEqualTo("AGUARDANDO_PAGAMENTO");
		assertThat(alerta(id)).contains("valor_divergente", "AGUARDANDO_PAGAMENTO", "R$ 30,01");

		caixa.limpar();
		mp(PAG, "approved", id, "30.01", "USD");
		assertThat(status(notificar(2))).isEqualTo("ignored");
		assertThat(evento(2)).isEqualTo("IGNORADO valor_divergente");
		alerta(id);

		mp(PAG, "approved", id, "30.010", "BRL"); // mesma quantia em outra escala: confere
		assertThat(status(notificar(3))).isEqualTo("ok");
		assertThat(contar("select count(*) from repasse")).isEqualTo(1);
	}

	@Test
	void pagamentoOrfaoDePedidoJaPagoAlertaSemSegundoRepasse() throws Exception {
		var id = pedido();
		cobranca(id, 1, PAG);
		cobranca(id, 2, PAG + 1);
		aprovado(PAG, id);
		assertThat(status(notificar(1))).isEqualTo("ok");

		aprovado(PAG + 1, id); // a cobranca vencida tambem foi paga
		assertThat(status(notificar(2, PAG + 1, "payment"))).isEqualTo("ignored");

		assertThat(evento(2)).isEqualTo("IGNORADO pagamento_orfao");
		assertThat(alerta(id)).contains("pagamento_orfao", String.valueOf(PAG + 1));
		assertThat(contar("select count(*) from repasse")).isEqualTo(1);
	}

	@Test
	void pagamentoQueNaoEDeCobrancaDoPedidoNaoPaga() throws Exception {
		var id = pedido();
		cobranca(id, 1, PAG);
		aprovado(PAG + 9, id);

		assertThat(status(notificar(1, PAG + 9, "payment"))).isEqualTo("ignored");
		assertThat(evento(1)).isEqualTo("IGNORADO cobranca_desconhecida");
		assertThat(statusDoPedido(id)).isEqualTo("AGUARDANDO_PAGAMENTO");
		assertThat(alerta(id)).contains("cobranca_desconhecida");
	}

	@Test
	void pedidoInexistenteOuReferenciaInvalidaFicaIgnorado() throws Exception {
		var inexistente = UUID.randomUUID();
		mp(PAG, "approved", inexistente, "30.01", "BRL");
		assertThat(status(notificar(1))).isEqualTo("ignored");
		assertThat(evento(1)).isEqualTo("IGNORADO pedido_inexistente");
		assertThat(alerta(inexistente)).as("dinheiro aprovado sem pedido: alerta").contains("pedido_inexistente");
		mp(PAG, "approved", "nao-e-uuid", "30.01", "BRL");
		assertThat(status(notificar(2))).isEqualTo("ignored");
		assertThat(evento(2)).isEqualTo("IGNORADO pedido_inexistente");
		mp(PAG, "approved", null, "30.01", "BRL");
		assertThat(status(notificar(3))).isEqualTo("ignored");
		assertThat(evento(3)).isEqualTo("IGNORADO pedido_inexistente");
		mp(PAG, "refunded", UUID.randomUUID(), "30.01", "BRL");
		assertThat(status(notificar(4))).isEqualTo("ignored");
		assertThat(caixa.enviados()).as("referencia invalida ou estorno: sem alerta").hasSize(1);
	}

	@Test
	void pagamentoNaoAprovadoFicaIgnorado() throws Exception {
		var id = pedido();
		cobranca(id, 1, PAG);
		mp(PAG, "pending", id, "30.01", "BRL");
		assertThat(status(notificar(1))).isEqualTo("ignored");
		assertThat(evento(1)).isEqualTo("IGNORADO nao_aprovado:pending");
		mp(PAG, "rejected", id, "30.01", "BRL");
		assertThat(status(notificar(2))).isEqualTo("ignored");
		assertThat(evento(2)).isEqualTo("IGNORADO nao_aprovado:rejected");
		assertThat(statusDoPedido(id)).isEqualTo("AGUARDANDO_PAGAMENTO");
	}

	@Test
	void dataIdQueNaoENumeroFicaIgnoradoSemBuscar() throws Exception {
		var ts = "1";
		var r = enviar("?data.id=abc", "{\"id\": 1, \"type\": \"payment\"}", "x-request-id", "r", "x-signature",
				"ts=" + ts + ",v1=" + assinar("abc", "r", ts));
		assertThat(status(r)).isEqualTo("ignored");
		assertThat(evento(1)).isEqualTo("IGNORADO data_id_invalido");
		assertThat(getsNoMp()).isZero();
	}

	@Test
	void buscaNoMp4xxIgnoraE5xxDeixaReprocessavel() throws Exception {
		MP.stubFor(get("/v1/payments/" + PAG).willReturn(aResponse().withStatus(404).withBody("{\"message\":\"not found\"}")));
		assertThat(status(notificar(1))).as("4xx nao transiente: 200, sem loop").isEqualTo("ignored");
		assertThat(evento(1)).isEqualTo("IGNORADO mp_404");

		var id = pedido();
		cobranca(id, 1, PAG);
		MP.stubFor(get("/v1/payments/" + PAG).willReturn(aResponse().withStatus(503).withBody("{}")));
		var r = notificar(2);
		assertThat(r.statusCode()).as("o MP reenvia").isEqualTo(500);
		assertThat(evento(2)).isEqualTo("ERRO mp_indisponivel");

		aprovado(PAG, id);
		assertThat(status(notificar(2))).as("ERRO nao e terminal: a reentrega processa").isEqualTo("ok");
		assertThat(evento(2)).startsWith("PROCESSADO");
	}

	// --- estorno (D9) ---

	@Test
	void estornoCancelaRepasseRegistradoEOPedidoNaoMuda() throws Exception {
		var id = pedido();
		cobranca(id, 1, PAG);
		aprovado(PAG, id);
		assertThat(status(notificar(1))).isEqualTo("ok");

		mp(PAG, "refunded", id, "30.01", "BRL");
		assertThat(status(notificar(2))).isEqualTo("ok");

		assertThat(evento(2)).isEqualTo("PROCESSADO repasse_cancelado");
		assertThat(statusDoPedido(id)).isEqualTo("PAGO");
		var repasse = jdbc.queryForMap("select status, motivo, cancelado_em from repasse where pedido_id = ?", id);
		assertThat(repasse).containsEntry("status", "CANCELADO").containsEntry("motivo", "refunded");
		assertThat(repasse.get("cancelado_em")).isNotNull();

		mp(PAG, "charged_back", id, "30.01", "BRL"); // segundo aviso: nada muda
		assertThat(status(notificar(3))).isEqualTo("ok");
		assertThat(jdbc.queryForObject("select motivo from repasse where pedido_id = ?", String.class, id)).isEqualTo("refunded");
		assertThat(caixa.para(ADMIN)).isEmpty();
	}

	@Test
	void estornoComRepasseJaTransferidoAlertaAcertoNoProximoCiclo() throws Exception {
		var id = pedido();
		cobranca(id, 1, PAG);
		aprovado(PAG, id);
		assertThat(status(notificar(1))).isEqualTo("ok");
		jdbc.update("update repasse set status = 'TRANSFERIDO', transferido_em = now(), comprovante = 'comp' where pedido_id = ?", id);

		mp(PAG, "charged_back", id, "30.01", "BRL");
		assertThat(status(notificar(2))).isEqualTo("ok");

		assertThat(evento(2)).isEqualTo("PROCESSADO repasse_transferido");
		assertThat(jdbc.queryForObject("select status from repasse where pedido_id = ?", String.class, id)).isEqualTo("TRANSFERIDO");
		assertThat(alerta(id)).contains("acerto no próximo ciclo", "R$ 6,00");
	}

	@Test
	void estornoDePagamentoQueNaoPagouOPedidoFicaIgnorado() throws Exception {
		var id = pedido();
		cobranca(id, 1, PAG);
		mp(PAG, "refunded", id, "30.01", "BRL");

		assertThat(status(notificar(1))).isEqualTo("ignored");
		assertThat(evento(1)).isEqualTo("IGNORADO estorno_sem_pagamento");
		assertThat(statusDoPedido(id)).isEqualTo("AGUARDANDO_PAGAMENTO");
	}

	@Test
	void disputaAbertaAlertaSemMexerNoRepasse() throws Exception {
		var id = pedido();
		cobranca(id, 1, PAG);
		aprovado(PAG, id);
		assertThat(status(notificar(1))).isEqualTo("ok");

		mp(PAG, "in_mediation", id, "30.01", "BRL");
		assertThat(status(notificar(2))).isEqualTo("ignored");

		assertThat(evento(2)).isEqualTo("IGNORADO em_mediacao");
		assertThat(alerta(id)).contains("em_mediacao", "disputa");
		assertThat(jdbc.queryForObject("select status from repasse where pedido_id = ?", String.class, id)).isEqualTo("REGISTRADO");
		assertThat(statusDoPedido(id)).isEqualTo("PAGO");
	}

	@Test
	void estornoParcialAlertaSemMexerNoRepasse() throws Exception {
		var id = pedido();
		cobranca(id, 1, PAG);
		aprovado(PAG, id);
		assertThat(status(notificar(1))).isEqualTo("ok");

		mp(PAG, "approved", id, "30.01", "BRL", "partially_refunded"); // o MP mantem approved no estorno parcial
		assertThat(status(notificar(2))).isEqualTo("ignored");

		assertThat(evento(2)).isEqualTo("IGNORADO estorno_parcial");
		assertThat(alerta(id)).contains("estorno_parcial", "estorno parcial");
		assertThat(jdbc.queryForObject("select status from repasse where pedido_id = ?", String.class, id)).isEqualTo("REGISTRADO");
	}

	// --- falha interna ---

	/** Banco ou conexao caindo no meio: 5xx para o MP reentregar sozinho, sem alerta; o evento fica reprocessavel. */
	@Test
	void falhaDeInfraestruturaDevolve5xxParaOMpReentregar() throws Exception {
		var id = pedido();
		cobranca(id, 1, PAG);
		aprovado(PAG, id);
		var n = 0L;
		for (var falha : List.of(new DataAccessResourceFailureException("conexao caiu (teste)"),
				new RecoverableDataAccessException("banco reiniciando (teste)"))) {
			doThrow(falha).when(cobrancas).registrarPagamento(anyLong(), any());
			var r = notificar(++n);
			assertThat(r.statusCode()).as(falha.getClass().getSimpleName()).isEqualTo(500);
			assertThat(evento(n)).as("nao terminal: a reentrega processa").isEqualTo("ERRO erro_interno");
			assertThat(statusDoPedido(id)).isEqualTo("AGUARDANDO_PAGAMENTO");
		}
		org.mockito.Mockito.reset(cobrancas);
		assertThat(status(notificar(1))).isEqualTo("ok");
		assertThat(contar("select count(*) from repasse")).isEqualTo(1);
		for (int i = 0; i < 500 && envio.pendentes() > 0; i++) Thread.sleep(10); // um alerta, se houvesse, ja teria saido
		assertThat(caixa.para(ADMIN)).as("infra: sem alerta").isEmpty();
	}


	/**
	 * O e-mail estoura dentro do provisionamento (coluna de 254): a transacao inteira volta, o evento fica ERRO numa
	 * transacao propria e a resposta e 200 (o MP para de reenviar; o admin reprocessa depois de corrigir).
	 */
	@Test
	void falhaNoProvisionamentoMarcaErroSemLoopDeReenvio() throws Exception {
		var id = pedido(3001, instituicao(), "a".repeat(290) + "@exemplo.com");
		cobranca(id, 1, PAG);
		aprovado(PAG, id);

		assertThat(status(notificar(1))).isEqualTo("error");

		assertThat(evento(1)).isEqualTo("ERRO erro_interno");
		assertThat(statusDoPedido(id)).isEqualTo("AGUARDANDO_PAGAMENTO");
		assertThat(contar("select count(*) from repasse")).isZero();
		assertThat(contar("select count(*) from cobranca where paga_em is not null")).isZero();
		assertThat(alerta(id)).contains("erro_interno");
	}

	// --- concorrencia ---

	List<String> emParalelo(List<Long> ids) throws Exception {
		var largada = new CountDownLatch(1);
		var status = new ArrayList<String>();
		try (var threads = Executors.newFixedThreadPool(ids.size())) {
			List<Future<HttpResponse<String>>> f = ids.stream().map(n -> threads.submit((Callable<HttpResponse<String>>) () -> {
				largada.await();
				return notificar(n);
			})).toList();
			largada.countDown();
			for (var r : f) status.add(status(r.get()));
		}
		return status;
	}

	void aprovadoDevagar(UUID id) {
		MP.stubFor(get("/v1/payments/" + PAG).willReturn(okJson("""
				{"id": %d, "status": "approved", "external_reference": "%s", "transaction_amount": 30.01, "currency_id": "BRL"}"""
				.formatted(PAG, id)).withFixedDelay(300))); // as notificacoes chegam juntas a trava do pedido
	}

	@Test
	void mesmaNotificacaoEmParaleloConfirmaUmaVez() throws Exception {
		var id = pedido();
		cobranca(id, 1, PAG);
		aprovadoDevagar(id);

		var status = emParalelo(List.of(1L, 1L, 1L));

		assertThat(status).filteredOn("ok"::equals).hasSize(1);
		assertThat(status).filteredOn(s -> !s.equals("ok")).allMatch(s -> s.equals("already_paid") || s.equals("already_processed"));
		assertThat(contar("select count(*) from repasse")).isEqualTo(1);
		assertThat(contar("select count(*) from usuario where email = 'maria@exemplo.com'")).isEqualTo(1);
		assertThat(contar("select count(*) from pagamento_evento")).isEqualTo(1);
		assertThat(evento(1)).startsWith("PROCESSADO");
	}

	@Test
	void notificacoesDiferentesDoMesmoPagamentoEmParaleloConfirmamUmaVez() throws Exception {
		var id = pedido();
		cobranca(id, 1, PAG);
		aprovadoDevagar(id);

		var status = emParalelo(List.of(1L, 2L, 3L, 4L));

		assertThat(status).filteredOn("ok"::equals).hasSize(1);
		assertThat(status).filteredOn(s -> !s.equals("ok")).allMatch("already_paid"::equals);
		assertThat(statusDoPedido(id)).isEqualTo("PAGO");
		assertThat(contar("select count(*) from repasse")).isEqualTo(1);
		assertThat(contar("select count(*) from usuario where email = 'maria@exemplo.com'")).isEqualTo(1);
		assertThat(contar("select count(*) from pagamento_evento where status = 'PROCESSADO'")).isEqualTo(1);
		assertThat(contar("select count(*) from pagamento_evento where status = 'IGNORADO'")).isEqualTo(3);
	}

	static JsonNode json(HttpResponse<String> r) {
		return Navegador.json(r);
	}
}
