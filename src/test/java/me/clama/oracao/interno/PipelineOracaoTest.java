package me.clama.oracao.interno;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import me.clama.BancoDeTeste;
import me.clama.CaixaDeSaidaEmMemoria;
import me.clama.EmailDeTeste;
import me.clama.conta.web.Navegador;
import me.clama.infra.cripto.HmacDeIdentificador;
import me.clama.infra.email.EnvioAssincrono;
import me.clama.infra.fila.Fila;
import me.clama.infra.fila.FilaLigada;
import me.clama.pedido.PedidoApi;
import me.clama.pedido.StatusPedido;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * O pipeline com a fila ligada e a Claude num WireMock: gerar, enviar, reenviar, alerta (D11) e a varredura (INV:105,
 * 108-113). Esperas curtas por propriedade; a politica (3 retentativas, ate 3 novas tentativas) e a de producao.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import({ BancoDeTeste.class, EmailDeTeste.class })
@ActiveProfiles("test")
@FilaLigada
class PipelineOracaoTest {

	static final WireMockServer CLAUDE = new WireMockServer(WireMockConfiguration.options().dynamicPort());
	static final String ADMIN = "contato@clama.me";
	static final String DOADORA = "maria@exemplo.com";
	static final String ORACAO = "Senhor, acolhe a Maria & sua família.\n\nAmém.";
	static final String OK = """
			{"id": "msg_1", "type": "message", "role": "assistant", "model": "claude-sonnet-4-6",
			 "content": [{"type": "text", "text": "Senhor, acolhe a Maria & sua família.\\n\\nAmém."}], "stop_reason": "end_turn",
			 "stop_sequence": null, "usage": {"input_tokens": 812, "output_tokens": 164}}""";

	static {
		CLAUDE.start();
	}

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		r.add("clama.oracao.anthropic-url", CLAUDE::baseUrl);
		r.add("clama.oracao.anthropic-api-key", () -> "sk-ant-teste");
		r.add("clama.oracao.espera-geracao", () -> "100ms");
		r.add("clama.oracao.espera-nova-tentativa", () -> "500ms");
		r.add("clama.oracao.espera-envio", () -> "100ms");
		r.add("clama.oracao.espera-alerta", () -> "100ms");
	}

	@LocalServerPort int porta;
	@Autowired JdbcTemplate jdbc;
	@Autowired PedidoRepository pedidos;
	@Autowired HmacDeIdentificador hmac;
	@Autowired CaixaDeSaidaEmMemoria caixa;
	@Autowired EnvioAssincrono envio;
	@Autowired TransactionTemplate tx;
	@Autowired Fila fila;
	@Autowired JobGerarOracao gerar;
	@Autowired JobEnviarOracao enviar;
	@Autowired VarreduraDePedidosPresos varredura;
	@Autowired JobAlertaAdmin alertaAdmin;
	@MockitoSpyBean PedidoApi api;

	@BeforeEach
	@AfterEach
	void limpar() throws Exception {
		for (int i = 0; i < 500 && envio.pendentes() > 0; i++) Thread.sleep(10);
		CLAUDE.resetAll();
		caixa.limpar();
		jdbc.update(BancoDeTeste.LIMPAR_FILA); // gerar, enviar, reenviar e alerta-admin
		jdbc.update("delete from pedido");
		jdbc.update("delete from plano where tipo = 'COMUM'");
		jdbc.update("delete from documento_contexto");
	}

	// --- montagem ---

	UUID pedido() {
		var plano = jdbc.queryForObject("""
				insert into plano (nome, valor_centavos, complexidade, tipo, ordem) values (?, 3000, 'SIMPLES', 'COMUM', 1)
				returning id""", Long.class, "Oração Simples " + UUID.randomUUID());
		var agora = Instant.now();
		var d = new Pedido.Dados(null, plano, null, 3000, "Maria Souza", DOADORA, "52998224725", "11999990000", (short) 40,
				"feminino", "Pela família", agora, null, "1");
		return pedidos.saveAndFlush(Pedido.novo(d, hmac, agora)).getId();
	}

	/** Pedido pronto para gerar: a gratuidade publica GeracaoSolicitada, e o ouvinte enfileira. */
	UUID pedidoPago() {
		var id = pedido();
		api.concederGratuidade(id);
		return id;
	}

	static void claude(int status, String corpo) {
		CLAUDE.stubFor(post("/v1/messages").willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(corpo)));
	}

	static int chamadasAClaude() {
		return CLAUDE.findAll(postRequestedFor(urlEqualTo("/v1/messages"))).size();
	}

	String status(UUID id) {
		return jdbc.queryForObject("select status from pedido where id = ?", String.class, id);
	}

	String ultimoErro(UUID id) {
		return jdbc.queryForObject("select ultimo_erro from pedido where id = ?", String.class, id);
	}

	void aguardarStatus(UUID id, StatusPedido s) {
		await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(20)).until(() -> status(id).equals(s.name()));
	}

	List<CaixaDeSaidaEmMemoria.Enviado> oracoes() {
		return caixa.para(DOADORA).stream().filter(e -> e.email().assunto().startsWith("Sua oração")).toList();
	}

	/** O alerta (D11): para o admin, chave por hora UTC, sem PII. */
	String alerta(UUID id) {
		var enviados = caixa.aguardarPara(ADMIN, 1);
		assertThat(enviados).hasSize(1);
		var a = enviados.getFirst();
		var hora = Instant.now().truncatedTo(ChronoUnit.HOURS);
		assertThat(a.chave()).matches("alerta/" + id + "/[a-z_]+/(" + hora + "|" + hora.minus(Duration.ofHours(1)) + ")");
		assertThat(a.email().texto() + a.email().html() + a.email().assunto()).doesNotContain("Maria", "maria@", "52998224725",
				"Pela família", "11999990000");
		assertThat(a.email().texto()).contains(id.toString(), "/admin/pedidos?id=" + id);
		return a.email().texto();
	}

	void semMudarPor(Duration d) throws InterruptedException {
		Thread.sleep(d.toMillis());
	}

	// --- geracao e envio ---

	@Test
	void pedidoPagoGeraEnviaEMarcaEnviada() throws Exception {
		claude(200, OK);
		var id = pedidoPago();

		aguardarStatus(id, StatusPedido.ENVIADA);

		var dados = api.dadosParaOracao(id);
		assertThat(dados.oracao()).isEqualTo(ORACAO);
		assertThat(dados.ultimoErro()).isNull();
		CLAUDE.verify(1, postRequestedFor(urlEqualTo("/v1/messages"))
				.withRequestBody(matchingJsonPath("$.messages[0].content", containing("Pedido: Pela família")))
				.withRequestBody(matchingJsonPath("$.messages[0].content", containing("Nome: Maria Souza"))));
		var emails = oracoes();
		assertThat(emails).hasSize(1);
		var e = emails.getFirst();
		assertThat(e.chave()).isEqualTo("oracao/" + id);
		assertThat(e.email().assunto()).isEqualTo("Sua oração chegou, Maria");
		assertThat(e.email().texto()).contains("Paz e bem, Maria.", ORACAO, "https://wa.me/?text=");
		assertThat(e.email().html()).contains("Senhor, acolhe a Maria &amp; sua", "https://wa.me/?text=");
		assertThat(e.email().texto()).doesNotContain("senha");
		assertThat(caixa.para(ADMIN)).isEmpty();
	}

	@Test
	void jobRodadoDuasVezesNaoChamaAClaudeNemEnviaDeNovo() throws Exception {
		claude(200, OK);
		var id = pedidoPago();
		aguardarStatus(id, StatusPedido.ENVIADA);

		// entrega pelo menos uma vez: a mesma instancia roda de novo (heartbeat, deploy)
		gerar.executar(id, 0);
		enviar.executar(id, 0);
		semMudarPor(Duration.ofMillis(500));

		assertThat(chamadasAClaude()).isEqualTo(1);
		assertThat(oracoes()).hasSize(1);
		assertThat(status(id)).isEqualTo("ENVIADA");
	}

	@Test
	void geracaoRepetidaComOracaoGeradaSoGaranteOEnvio() throws Exception {
		claude(200, OK);
		var id = pedido();
		jdbc.update("update pedido set status = 'GERANDO_ORACAO' where id = ?", id);
		api.registrarOracao(id, "Oração já gerada.");

		gerar.executar(id, 0); // a 1a execucao gravou e caiu antes de enfileirar o envio

		aguardarStatus(id, StatusPedido.ENVIADA);
		assertThat(chamadasAClaude()).isZero();
		assertThat(oracoes()).singleElement().satisfies(e -> assertThat(e.email().texto()).contains("Oração já gerada."));
	}

	@Test
	void nomeVazioViraAmada() {
		assertThat(JobEnviarOracao.primeiroNome("  ")).isEqualTo("Amada");
		assertThat(JobEnviarOracao.primeiroNome(null)).isEqualTo("Amada");
		assertThat(JobEnviarOracao.primeiroNome(" Maria  Souza")).isEqualTo("Maria");
	}

	@Test
	void jobDeGeracaoNaoGeraPedidoAguardandoPagamento() throws Exception {
		claude(200, OK);
		var id = pedido();

		tx.executeWithoutResult(s -> fila.enfileirar(gerar, gerar.instancia(id), id, Instant.now()));

		await().atMost(Duration.ofSeconds(5)).until(() -> !fila.existePendente(gerar.getName(), gerar.instancia(id)));
		assertThat(status(id)).isEqualTo("AGUARDANDO_PAGAMENTO");
		assertThat(chamadasAClaude()).isZero();
	}

	@Test
	void rollbackDoPublicadorNaoEnfileiraNada() throws Exception {
		claude(200, OK);
		var id = pedido();

		tx.executeWithoutResult(s -> {
			api.concederGratuidade(id);
			assertThat(fila.existePendente(gerar.getName(), gerar.instancia(id))).as("na mesma transacao").isTrue();
			s.setRollbackOnly();
		});
		semMudarPor(Duration.ofMillis(800));

		assertThat(fila.existePendente(gerar.getName(), gerar.instancia(id))).isFalse();
		assertThat(status(id)).isEqualTo("AGUARDANDO_PAGAMENTO");
		assertThat(chamadasAClaude()).isZero();
	}

	@Test
	void creditosEsgotadosVaiParaErroSemRetentarComAlertaEMensagemPastoral() throws Exception {
		claude(400, """
				{"type": "error", "error": {"type": "invalid_request_error", "message":
				 "Your credit balance is too low to access the Anthropic API."}}""");
		var id = pedidoPago();

		aguardarStatus(id, StatusPedido.ERRO);
		semMudarPor(Duration.ofMillis(500));

		assertThat(ultimoErro(id)).isEqualTo("credit_balance");
		assertThat(chamadasAClaude()).isEqualTo(1);
		assertThat(alerta(id)).contains("credit_balance");
		var publico = Navegador.json(new Navegador(porta).get("/api/pedidos/" + id));
		assertThat(publico.get("status").asString()).isEqualTo("ERRO");
		assertThat(publico.get("mensagemPastoral").asString()).contains("24 horas");
		assertThat(oracoes()).isEmpty();
	}

	@Test
	void documentoQueSumiuNaFilesApiFalhaSemRetentarEAlertaConferirDocumentos() throws Exception {
		jdbc.update("""
				insert into documento_contexto (nome, mime, tamanho_bytes, file_id, ativo, criado_em, atualizado_em)
				values ('Catecismo', 'application/pdf', 10, 'file_sumiu', true, now(), now())""");
		claude(404, """
				{"type": "error", "error": {"type": "not_found_error", "message": "File not found: file_sumiu"}}""");
		var id = pedidoPago();

		aguardarStatus(id, StatusPedido.ERRO);
		semMudarPor(Duration.ofMillis(500));

		assertThat(ultimoErro(id)).isEqualTo("falha_geracao");
		assertThat(chamadasAClaude()).isEqualTo(1);
		CLAUDE.verify(postRequestedFor(urlEqualTo("/v1/messages"))
				.withRequestBody(matchingJsonPath("$.messages[0].content[0].source.file_id", equalTo("file_sumiu"))));
		assertThat(alerta(id)).contains("falha_geracao", "conferir documentos ativos");
	}

	@Test
	void documentoTrocadoNoMeioDaChamadaRetentaEmVezDeFalharOPedido() throws Exception {
		jdbc.update("""
				insert into documento_contexto (nome, mime, tamanho_bytes, file_id, ativo, criado_em, atualizado_em)
				values ('Catecismo', 'application/pdf', 10, 'file_velho', true, now(), now())""");
		CLAUDE.stubFor(post("/v1/messages").willReturn(aResponse().withStatus(404).withFixedDelay(600).withHeader("Content-Type", "application/json")
				.withBody("""
						{"type": "error", "error": {"type": "not_found_error", "message": "File not found"}}""")));
		var id = pedidoPago();
		Thread.sleep(300); // a chamada ja saiu com o arquivo antigo e segue presa no atraso do stub
		jdbc.update("update documento_contexto set file_id = 'file_novo'"); // o admin trocou o arquivo durante a chamada

		aguardarStatus(id, StatusPedido.ERRO);

		assertThat(chamadasAClaude()).isEqualTo(2); // 1a com o antigo, a retentativa com o novo; ai sim e falha de verdade
		CLAUDE.verify(1, postRequestedFor(urlEqualTo("/v1/messages"))
				.withRequestBody(matchingJsonPath("$.messages[0].content[0].source.file_id", equalTo("file_novo"))));
		assertThat(alerta(id)).contains("falha_geracao");
	}

	@Test
	void alertaRetentaQuandoAResendEstaForaEChegaQuandoVolta() throws Exception {
		var id = pedido();
		caixa.falhar(true);

		tx.executeWithoutResult(s -> alertaAdmin.enfileirar(id, "falha_envio"));
		semMudarPor(Duration.ofMillis(500));
		assertThat(caixa.para(ADMIN)).isEmpty();
		caixa.falhar(false);

		assertThat(alerta(id)).contains("falha_envio");
	}

	@Test
	void motivosDiferentesNaMesmaHoraTemChavesDiferentesEOMesmoMotivoAMesma() throws Exception {
		var id = pedido();

		for (var motivo : new String[] { "credit_balance", "falha_geracao", "falha_geracao" }) {
			tx.executeWithoutResult(s -> alertaAdmin.enfileirar(id, motivo));
			for (int i = 0; i < 300 && fila.existePendente(alertaAdmin.getName(), alertaAdmin.instancia(id + "/" + motivo)); i++) Thread.sleep(10);
		}
		semMudarPor(Duration.ofMillis(500));

		// a caixa de teste nao deduplica como a Resend: o que conta e a chave (igual = descartado la)
		var chaves = caixa.para(ADMIN).stream().map(CaixaDeSaidaEmMemoria.Enviado::chave).distinct().toList();
		assertThat(chaves).hasSize(2);
		assertThat(chaves).anyMatch(c -> c.contains("/credit_balance/")).anyMatch(c -> c.contains("/falha_geracao/"));
	}

	@Test
	void falhaPassageiraRetentaReagendaEDepoisVaiParaErroComAlerta() throws Exception {
		claude(500, """
				{"type": "error", "error": {"type": "api_error", "message": "Internal server error"}}""");
		var id = pedidoPago();

		aguardarStatus(id, StatusPedido.AGUARDANDO_NOVA_TENTATIVA);
		assertThat(ultimoErro(id)).isEqualTo("falha_geracao");
		assertThat(chamadasAClaude()).isEqualTo(4); // 1 + 3 retentativas
		aguardarStatus(id, StatusPedido.ERRO);

		assertThat(jdbc.queryForObject("select tentativas from pedido where id = ?", Integer.class, id)).isEqualTo(3);
		assertThat(ultimoErro(id)).isEqualTo("falha_geracao");
		assertThat(chamadasAClaude()).isEqualTo(16); // 4 rodadas de 4
		assertThat(alerta(id)).contains("falha_geracao");
		assertThat(fila.existePendente(gerar.getName(), gerar.instancia(id))).isFalse();
	}

	@Test
	void marcacaoQueFalhaReenviaComAMesmaChave() throws Exception {
		claude(200, OK);
		var id = pedido();
		doThrow(new IllegalStateException("banco caiu")).doCallRealMethod().when(api).marcarEnviado(id);
		api.concederGratuidade(id);

		aguardarStatus(id, StatusPedido.ENVIADA);

		assertThat(oracoes()).extracting(CaixaDeSaidaEmMemoria.Enviado::chave).containsExactly("oracao/" + id, "oracao/" + id);
	}

	@Test
	void envioEsgotadoVaiParaErroComAlerta() throws Exception {
		claude(200, OK);
		caixa.recusar(e -> e.assunto().startsWith("Sua oração"));
		var id = pedidoPago();

		aguardarStatus(id, StatusPedido.ERRO);

		assertThat(ultimoErro(id)).isEqualTo("falha_envio");
		assertThat(alerta(id)).contains("falha_envio");
		assertThat(api.dadosParaOracao(id).oracao()).isEqualTo(ORACAO); // a oracao fica para o reenvio do admin
	}

	// --- reenvio ---

	@Test
	void reenvioMandaDeNovoComChaveNovaEFicaEnviada() throws Exception {
		claude(200, OK);
		var id = pedidoPago();
		aguardarStatus(id, StatusPedido.ENVIADA);

		api.solicitarReenvio(id);

		await().atMost(Duration.ofSeconds(5)).until(() -> oracoes().size() == 2);
		var chave = oracoes().get(1).chave();
		assertThat(chave).startsWith("oracao/" + id + "/reenvio/");
		UUID.fromString(chave.substring(chave.lastIndexOf('/') + 1));
		assertThat(status(id)).isEqualTo("ENVIADA");
		assertThat(chamadasAClaude()).isEqualTo(1);
	}

	@Test
	void reenvioEsgotadoMantemEnviadaESoAlerta() throws Exception {
		claude(200, OK);
		var id = pedidoPago();
		aguardarStatus(id, StatusPedido.ENVIADA);
		caixa.recusar(e -> e.assunto().startsWith("Sua oração"));

		api.solicitarReenvio(id);

		assertThat(alerta(id)).contains("falha_reenvio");
		assertThat(status(id)).isEqualTo("ENVIADA");
		assertThat(ultimoErro(id)).isNull();
	}

	@Test
	void reenvioAPartirDeErroQueEsgotaVoltaParaErro() throws Exception {
		claude(200, OK);
		caixa.recusar(e -> e.assunto().startsWith("Sua oração"));
		var id = pedidoPago();
		aguardarStatus(id, StatusPedido.ERRO); // o envio esgotou: ERRO com a oracao gravada
		caixa.aguardarPara(ADMIN, 1);

		api.solicitarReenvio(id); // ERRO com oracao volta a ORACAO_GERADA

		assertThat(caixa.aguardarPara(ADMIN, 2)).hasSize(2);
		assertThat(status(id)).isEqualTo("ERRO");
		assertThat(ultimoErro(id)).isEqualTo("falha_envio");
	}

	@Test
	void reenvioComGeracaoOuEnvioNaFilaENoOp() throws Exception {
		claude(200, OK);
		var id = pedidoPago();
		aguardarStatus(id, StatusPedido.ENVIADA);
		tx.executeWithoutResult(s -> fila.enfileirar(enviar, enviar.instancia(id), id, Instant.now().plusSeconds(3600)));

		api.solicitarReenvio(id);
		semMudarPor(Duration.ofMillis(500));

		assertThat(fila.existePendente("reenviar-oracao", "reenviar-oracao/" + id)).isFalse();
		assertThat(oracoes()).hasSize(1);
	}

	// --- alerta (D11) ---

	@Test
	void alertaSoSaiDepoisDoCommitENaoSaiNoRollback() throws Exception {
		var id = pedido();

		tx.executeWithoutResult(s -> {
			alertaAdmin.enfileirar(id, "pedido_preso");
			s.setRollbackOnly();
		});
		semMudarPor(Duration.ofMillis(600));
		assertThat(caixa.para(ADMIN)).as("rollback").isEmpty();

		tx.executeWithoutResult(s -> {
			alertaAdmin.enfileirar(id, "pedido_preso");
			semMudarPorSemExcecao(Duration.ofMillis(600)); // a fila nao ve a linha antes do commit
			assertThat(caixa.para(ADMIN)).as("antes do commit").isEmpty();
		});
		assertThat(alerta(id)).contains("pedido_preso", "AGUARDANDO_PAGAMENTO", "Oração Simples", "R$");
		assertThat(fila.existePendente(alertaAdmin.getName(), alertaAdmin.instancia(id + "/pedido_preso"))).isFalse();
		assertThat(jdbc.queryForObject("select count(*) from scheduled_tasks", Integer.class)).isZero();
	}

	void semMudarPorSemExcecao(Duration d) {
		try {
			Thread.sleep(d.toMillis());
		} catch (InterruptedException e) {
			throw new IllegalStateException(e);
		}
	}

	// --- varredura ---

	UUID parado(String status, Duration ha) {
		var id = pedido();
		jdbc.update("update pedido set status = ?, atualizado_em = ? where id = ?", status,
				java.sql.Timestamp.from(Instant.now().minus(ha)), id);
		return id;
	}

	@Test
	void varreduraFalhaOParadoSemJobEPulaOQueTemJobOuAndouHaPouco() throws Exception {
		var preso = parado("PAGO", Duration.ofMinutes(30));
		var comJob = parado("ORACAO_GERADA", Duration.ofMinutes(30));
		var recente = parado("GERANDO_ORACAO", Duration.ofMinutes(5));
		tx.executeWithoutResult(s -> fila.enfileirar(enviar, enviar.instancia(comJob), comJob, Instant.now().plusSeconds(3600)));

		varredura.executar(false);

		assertThat(status(preso)).isEqualTo("ERRO");
		assertThat(ultimoErro(preso)).isEqualTo("pedido_preso");
		assertThat(alerta(preso)).contains("pedido_preso");
		assertThat(status(comJob)).isEqualTo("ORACAO_GERADA");
		assertThat(status(recente)).isEqualTo("GERANDO_ORACAO");
		assertThat(chamadasAClaude()).isZero(); // nunca regera
	}

	@Test
	void varreduraPulaPedidoComJobEmExecucao() throws Exception {
		var preso = parado("GERANDO_ORACAO", Duration.ofMinutes(30));
		tx.executeWithoutResult(s -> fila.enfileirar(gerar, gerar.instancia(preso), preso, Instant.now().plusSeconds(3600)));
		// outro no esta executando agora (a linha fica picked ate o job terminar)
		jdbc.update("update scheduled_tasks set picked = true, picked_by = 'outro-no', last_heartbeat = now() where task_instance = ?",
				gerar.instancia(preso));

		varredura.executar(false);

		assertThat(status(preso)).isEqualTo("GERANDO_ORACAO");
		assertThat(caixa.para(ADMIN)).isEmpty();
	}

	@Test
	void varreduraEmDryRunNaoMudaNada() throws Exception {
		var preso = parado("AGUARDANDO_NOVA_TENTATIVA", Duration.ofMinutes(30));

		varredura.executar(true);
		semMudarPor(Duration.ofMillis(300));

		assertThat(status(preso)).isEqualTo("AGUARDANDO_NOVA_TENTATIVA");
		assertThat(caixa.para(ADMIN)).isEmpty();
	}

	@Test
	void varreduraRodaACada10MinutosEmSaoPaulo() {
		assertThat(varredura.getName()).isEqualTo("pedidos-presos");
		assertThat(varredura.agenda().getInitialExecutionTime(Instant.parse("2026-11-11T13:03:00Z"))).isEqualTo(Instant.parse("2026-11-11T13:10:00Z"));
	}
}
