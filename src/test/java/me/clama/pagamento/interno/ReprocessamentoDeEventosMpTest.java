package me.clama.pagamento.interno;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import me.clama.BancoDeTeste;
import me.clama.CaixaDeSaidaEmMemoria;
import me.clama.EmailDeTeste;
import me.clama.infra.cripto.HmacDeIdentificador;
import me.clama.infra.email.EnvioAssincrono;
import me.clama.pedido.interno.Pedido;
import me.clama.pedido.interno.PedidoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Tarefa {@code reprocessar-eventos-mp} contra um WireMock no lugar do Mercado Pago. */
@SpringBootTest
@Import({ BancoDeTeste.class, EmailDeTeste.class })
@ActiveProfiles("test") // base do MP em http (WireMock) so e aceita no perfil de teste
class ReprocessamentoDeEventosMpTest {

	static final WireMockServer MP = new WireMockServer(WireMockConfiguration.options().dynamicPort());

	static {
		MP.start();
	}

	@DynamicPropertySource
	static void mercadoPago(DynamicPropertyRegistry r) {
		r.add("clama.pagamento.mercadopago-url", MP::baseUrl);
		r.add("clama.pagamento.mercadopago-token", () -> "test");
	}

	@Autowired ReprocessamentoDeEventosMp tarefa;
	@Autowired JdbcTemplate jdbc;
	@Autowired PedidoRepository pedidos;
	@Autowired HmacDeIdentificador hmac;
	@Autowired EnvioAssincrono envio;
	@Autowired CaixaDeSaidaEmMemoria caixa;
	@Autowired EmailDeTeste.RelogioAvancavel relogio;

	@BeforeEach
	@AfterEach
	void limpar() throws Exception {
		for (int i = 0; i < 500 && envio.pendentes() > 0; i++) Thread.sleep(10); // o link de senha grava token
		MP.resetAll();
		caixa.limpar();
		relogio.zerar();
		jdbc.update("delete from pagamento_evento");
		jdbc.update("delete from repasse");
		jdbc.update("delete from cobranca");
		jdbc.update(me.clama.BancoDeTeste.LIMPAR_FILA); // GeracaoSolicitada enfileira gerar-oracao e comita
		jdbc.update("delete from pedido");
		jdbc.update("delete from usuario where email like '%@exemplo.com'");
		jdbc.update("delete from plano where tipo = 'COMUM'");
	}

	UUID pedidoComCobranca(long mpId) {
		var plano = jdbc.queryForObject("""
				insert into plano (nome, valor_centavos, complexidade, tipo, ordem) values (?, 2000, 'SIMPLES', 'COMUM', 1)
				returning id""", Long.class, "Pedido de Oração " + UUID.randomUUID());
		var agora = Instant.now();
		var d = new Pedido.Dados(null, plano, null, 3001L, "Maria Souza", "maria@exemplo.com", "52998224725", null, null, null,
				"Pela família", agora, null, "1");
		var id = pedidos.saveAndFlush(Pedido.novo(d, hmac, agora)).getId();
		jdbc.update("""
				insert into cobranca (pedido_id, n, mp_pagamento_id, pix_copia_e_cola, expira_em, criada_em)
				values (?, 1, ?, 'qr', now() + interval '1 day', now())""", id, mpId);
		return id;
	}

	/** Evento como o webhook deixa quando o GET ao MP falha; {@code idade}: ha quanto tempo chegou e falhou. */
	void evento(String idExterno, long mpId, String status, String motivo, Duration idade) {
		var quando = java.sql.Timestamp.from(Instant.now().minus(idade));
		jdbc.update("""
				insert into pagamento_evento (id_externo, tipo, mp_pagamento_id, payload, status, motivo, recebido_em, atualizado_em)
				values (?, 'payment', ?, '{}'::jsonb, ?, ?, ?, ?)""", idExterno, String.valueOf(mpId), status, motivo, quando,
				quando);
	}

	static void aprovado(long mpId, UUID pedido) {
		MP.stubFor(get("/v1/payments/" + mpId).willReturn(okJson("""
				{"id": %d, "status": "approved", "status_detail": "accredited", "external_reference": "%s",
				 "transaction_amount": 30.01, "currency_id": "BRL"}""".formatted(mpId, pedido))));
	}

	String evento(String idExterno) {
		return jdbc.queryForObject("select status || ' ' || coalesce(motivo, '-') from pagamento_evento where id_externo = ?",
				String.class, idExterno);
	}

	int getsNoMp(long mpId) {
		return MP.findAll(getRequestedFor(urlEqualTo("/v1/payments/" + mpId))).size();
	}

	@Test
	void reprocessaOsQueFalharamPorMpIndisponivelNaJanela() {
		var pedido = pedidoComCobranca(101);
		aprovado(101, pedido);
		evento("elegivel", 101, "ERRO", "mp_indisponivel", Duration.ofHours(1));
		evento("recente", 102, "ERRO", "mp_indisponivel", Duration.ofMinutes(5));
		evento("velho", 103, "ERRO", "mp_indisponivel", Duration.ofDays(4));
		evento("outro-motivo", 104, "ERRO", "erro_interno", Duration.ofHours(1));
		evento("recebido", 105, "RECEBIDO", null, Duration.ofHours(1));

		assertThat(tarefa.executar(false)).isEqualTo("1 de 1 evento(s) tentados");

		assertThat(evento("elegivel")).isEqualTo("PROCESSADO -");
		assertThat(jdbc.queryForObject("select status from pedido where id = ?", String.class, pedido)).isEqualTo("PAGO");
		assertThat(evento("recente")).isEqualTo("ERRO mp_indisponivel");
		assertThat(evento("velho")).isEqualTo("ERRO mp_indisponivel_expirado");
		assertThat(evento("outro-motivo")).isEqualTo("ERRO erro_interno");
		assertThat(evento("recebido")).isEqualTo("RECEBIDO -");
		for (long id : new long[] { 102, 103, 104, 105 }) assertThat(getsNoMp(id)).as("GET %d", id).isZero();
	}

	@Test
	void dryRunNaoChamaOMpNemAltera() throws Exception {
		var pedido = pedidoComCobranca(101);
		aprovado(101, pedido);
		evento("elegivel", 101, "ERRO", "mp_indisponivel", Duration.ofHours(1));
		evento("velho", 103, "ERRO", "mp_indisponivel", Duration.ofDays(4));

		assertThat(tarefa.executar(true)).isEqualTo("1 evento(s) seriam reprocessados (simulação)");

		assertThat(evento("elegivel")).isEqualTo("ERRO mp_indisponivel");
		assertThat(evento("velho")).isEqualTo("ERRO mp_indisponivel");
		assertThat(getsNoMp(101)).isZero();
		Thread.sleep(200);
		assertThat(caixa.enviados()).isEmpty();
	}

	/** Saiu da janela de 3 dias ainda em ERRO: um alerta so (sem PII), e o evento fica para o admin reprocessar. */
	@Test
	void foraDaJanelaAlertaUmaVez() throws Exception {
		evento("velho", 103, "ERRO", "mp_indisponivel", Duration.ofDays(4));

		tarefa.executar(false);
		tarefa.executar(false);

		var alertas = caixa.aguardarPara("contato@clama.me", 1);
		Thread.sleep(200);
		assertThat(caixa.enviados()).hasSize(1);
		assertThat(alertas.get(0).email().texto()).contains("mp_indisponivel_expirado", "103");
		var sem = alertas.get(0).email(); // alerta sem pedido: nada de "null" no assunto, no texto nem no link
		assertThat(sem.assunto() + sem.texto() + sem.html()).doesNotContain("null");
		assertThat(sem.assunto()).doesNotContain("pedido");
		assertThat(evento("velho")).isEqualTo("ERRO mp_indisponivel_expirado");
		assertThat(getsNoMp(103)).isZero();
	}

	/** O que sempre falha nao segura os outros: a tentativa remarca atualizado_em e ele vai para o fim da fila. */
	@Test
	void eventoQueSempreFalhaNaoSeguraOsMaisNovos() {
		MP.stubFor(get("/v1/payments/401").willReturn(aResponse().withStatus(500)));
		var pedido = pedidoComCobranca(402);
		aprovado(402, pedido);
		evento("sempre-falha", 401, "ERRO", "mp_indisponivel", Duration.ofHours(2));
		evento("mais-novo", 402, "ERRO", "mp_indisponivel", Duration.ofHours(1));

		tarefa.executar(false); // o mais antigo falha e encerra a rodada
		assertThat(evento("mais-novo")).isEqualTo("ERRO mp_indisponivel");
		relogio.avancar(Duration.ofHours(1));
		tarefa.executar(false);

		assertThat(evento("mais-novo")).isEqualTo("PROCESSADO -");
		assertThat(evento("sempre-falha")).isEqualTo("ERRO mp_indisponivel");
	}

	/** O MP continua fora: o evento fica ERRO para a proxima hora, e a rodada para (cada GET custa ate ~33 s). */
	@Test
	void mpAindaForaParaARodada() {
		MP.stubFor(get(urlPathMatching("/v1/payments/.*")).willReturn(aResponse().withStatus(401)));
		evento("primeiro", 201, "ERRO", "mp_indisponivel", Duration.ofHours(2));
		evento("segundo", 202, "ERRO", "mp_indisponivel", Duration.ofHours(1));

		tarefa.executar(false);

		assertThat(evento("primeiro")).isEqualTo("ERRO mp_indisponivel");
		assertThat(evento("segundo")).isEqualTo("ERRO mp_indisponivel");
		assertThat(getsNoMp(201)).isEqualTo(1);
		assertThat(getsNoMp(202)).isZero();
	}

	@Test
	void limitePorRodada() {
		// pagamento pendente: o processamento so marca IGNORADO, sem pedido
		MP.stubFor(get(urlPathMatching("/v1/payments/.*")).willReturn(okJson("""
				{"id": 1, "status": "pending", "status_detail": "pending_waiting_transfer"}""")));
		for (int i = 0; i < ReprocessamentoDeEventosMp.LIMITE + 1; i++)
			evento("e" + i, 300 + i, "ERRO", "mp_indisponivel", Duration.ofHours(1).plusSeconds(i));

		tarefa.executar(false);

		assertThat(jdbc.queryForObject("select count(*) from pagamento_evento where status = 'IGNORADO'", Integer.class))
				.isEqualTo(ReprocessamentoDeEventosMp.LIMITE);
		// os mais antigos primeiro: sobra o mais novo
		assertThat(evento("e0")).isEqualTo("ERRO mp_indisponivel");
	}

	@Test
	void roda1VezPorHoraNoFusoDeSaoPaulo() {
		assertThat(tarefa.getName()).isEqualTo("reprocessar-eventos-mp");
		var t0 = Instant.parse("2026-11-05T15:20:00Z");
		var t1 = tarefa.agenda().getInitialExecutionTime(t0);
		assertThat(Duration.between(t0, t1)).isPositive().isLessThanOrEqualTo(Duration.ofHours(1));
	}
}
