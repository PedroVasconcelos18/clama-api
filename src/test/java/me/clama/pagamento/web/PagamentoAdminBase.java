package me.clama.pagamento.web;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import me.clama.BancoDeTeste;
import me.clama.CaixaDeSaidaEmMemoria;
import me.clama.EmailDeTeste;
import me.clama.conta.interno.Papel;
import me.clama.conta.interno.UsuarioRepository;
import me.clama.conta.web.Navegador;
import me.clama.infra.cripto.HmacDeIdentificador;
import me.clama.infra.email.EnvioAssincrono;
import me.clama.infra.seguranca.LimiteDeTaxa;
import me.clama.pedido.interno.Pedido;
import me.clama.pedido.interno.PedidoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/** Base dos testes do admin de eventos e repasses: MP em WireMock, banco limpo antes e depois, admin e cliente logados. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import({ BancoDeTeste.class, EmailDeTeste.class })
@ActiveProfiles("test")
abstract class PagamentoAdminBase {

	static final WireMockServer MP = new WireMockServer(WireMockConfiguration.options().dynamicPort());
	static final long PAG = 1325814423L;

	static {
		MP.start();
	}

	@DynamicPropertySource
	static void mercadoPago(DynamicPropertyRegistry r) {
		r.add("clama.pagamento.mercadopago-url", MP::baseUrl);
		r.add("clama.pagamento.mercadopago-token", () -> "test");
		r.add("clama.pagamento.webhook-segredo", () -> "segredo-do-webhook-de-teste");
	}

	@LocalServerPort int porta;
	@Autowired JdbcTemplate jdbc;
	@Autowired PedidoRepository pedidos;
	@Autowired HmacDeIdentificador hmac;
	@Autowired UsuarioRepository usuarios;
	@Autowired PasswordEncoder encoder;
	@Autowired LimiteDeTaxa limite;
	@Autowired CaixaDeSaidaEmMemoria caixa;
	@Autowired EnvioAssincrono envio;
	@Autowired me.clama.EmailDeTeste.RelogioAvancavel relogio;

	@BeforeEach
	@AfterEach
	void limpar() throws Exception {
		for (int i = 0; i < 500 && envio.pendentes() > 0; i++) Thread.sleep(10);
		limite.limpar();
		relogio.zerar();
		MP.resetAll();
		caixa.limpar();
		jdbc.update("delete from auditoria where entidade in ('pagamento_evento', 'repasse', 'repasse_lote', 'relatorio_interno')");
		jdbc.update("delete from pagamento_evento");
		jdbc.update("delete from relatorio_repasse");
		jdbc.update("delete from tarefa_execucao where tarefa = 'relatorio-repasse-mensal'");
		jdbc.update("delete from repasse");
		jdbc.update("delete from cobranca");
		jdbc.update(me.clama.BancoDeTeste.LIMPAR_FILA); // GeracaoSolicitada enfileira gerar-oracao e comita
		jdbc.update("delete from pedido");
		jdbc.update("delete from usuario where email like '%@exemplo.com'");
		jdbc.update("delete from plano where tipo = 'COMUM'");
		jdbc.update("delete from instituicao");
	}

	/** O relogio do teste passa a marcar este instante (UTC, ISO); {@code limpar} volta ao normal. */
	void relogioEm(String instanteUtc) {
		relogio.zerar();
		relogio.avancar(java.time.Duration.between(Instant.now(), Instant.parse(instanteUtc)));
	}

	Navegador entrar(Papel papel) throws Exception {
		var email = "adm-" + UUID.randomUUID() + "@exemplo.com";
		Navegador.usuario(usuarios, encoder, email, "senha-forte-123", papel);
		var nav = new Navegador(porta);
		assertThat(nav.login(email, "senha-forte-123").statusCode()).isEqualTo(200);
		return nav;
	}

	Long instituicao(String nome) {
		return jdbc.queryForObject("insert into instituicao (nome) values (?) returning id", Long.class, nome);
	}

	UUID pedido(long valor, Long instituicao) {
		return pedido("Maria Souza", valor, instituicao);
	}

	/** Pedido com nome, e-mail, CPF, telefone e texto de oracao conhecidos: o que nunca pode sair no CSV da instituicao. */
	UUID pedido(String nome, long valor, Long instituicao) {
		var plano = jdbc.queryForObject("""
				insert into plano (nome, valor_centavos, complexidade, tipo, ordem) values (?, 2000, 'SIMPLES', 'COMUM', 1)
				returning id""", Long.class, "Plano " + UUID.randomUUID());
		var agora = Instant.now();
		var d = new Pedido.Dados(null, plano, instituicao, valor, nome, "maria@exemplo.com", "52998224725", "11987654321", null, null,
				"Pela família", agora, null, "1");
		return pedidos.saveAndFlush(Pedido.novo(d, hmac, agora)).getId();
	}

	void cobranca(UUID pedido, int n, long mpId) {
		jdbc.update("""
				insert into cobranca (pedido_id, n, mp_pagamento_id, pix_copia_e_cola, expira_em, criada_em)
				values (?, ?, ?, 'qr', now() + interval '1 day', now())""", pedido, n, mpId);
	}

	/** A cobranca que pagou o pedido (no maximo uma): e o "id do pagamento no gateway" do CSV interno. */
	void paga(UUID pedido, long mpId) {
		cobranca(pedido, 1, mpId);
		jdbc.update("update cobranca set paga_em = now() where pedido_id = ?", pedido);
	}

	/** Repasse do pedido (base 3000, 20%, 600) com o instante de criacao dado (UTC). */
	long repasseEm(UUID pedido, Long instituicao, String status, String criadoEmUtc) {
		var id = repasse(pedido, instituicao, status);
		jdbc.update("update repasse set criado_em = ?::timestamptz where id = ?", criadoEmUtc, id);
		return id;
	}

	long evento(String idExterno, String status, String motivo) {
		return jdbc.queryForObject("""
				insert into pagamento_evento (id_externo, tipo, mp_pagamento_id, payload, status, motivo, recebido_em, atualizado_em)
				values (?, 'payment', ?, ?::jsonb, ?, ?, now(), now()) returning id""", Long.class, idExterno, String.valueOf(PAG),
				"{\"id\":\"" + idExterno + "\",\"type\":\"payment\",\"data\":{\"id\":\"" + PAG + "\"}}", status, motivo);
	}

	long repasse(UUID pedido, Long instituicao, String status) {
		var transferido = status.equals("TRANSFERIDO") ? "now()" : "null";
		var cancelado = status.equals("CANCELADO") ? "now()" : "null";
		return jdbc.queryForObject("insert into repasse (pedido_id, instituicao_id, valor_base_centavos, percentual, valor_centavos, "
				+ "status, criado_em, transferido_em, cancelado_em, comprovante) values (?, ?, 3000, 20, 600, ?, now(), " + transferido + ", "
				+ cancelado + ", " + (status.equals("TRANSFERIDO") ? "'comp-teste'" : "null") + ") returning id", Long.class, pedido, instituicao, status);
	}

	static void aprovado(UUID pedido) {
		MP.stubFor(get("/v1/payments/" + PAG).willReturn(okJson("""
				{"id": %d, "status": "approved", "status_detail": "accredited", "external_reference": "%s", "transaction_amount": 30.00,
				"currency_id": "BRL"}""".formatted(PAG, pedido))));
	}

	int contar(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	List<String> ids(HttpResponse<String> res) {
		assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
		var l = new ArrayList<String>();
		for (var n : json(res).get("content")) l.add(n.get("id").asString());
		return l;
	}

	static JsonNode json(HttpResponse<String> r) {
		return Navegador.json(r);
	}
}
