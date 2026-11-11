package me.clama.pagamento.web;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import me.clama.conta.interno.Papel;
import me.clama.pagamento.interno.ProcessamentoDePagamento;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Admin de eventos do Mercado Pago: lista, detalhe e reprocessamento (so RECEBIDO/ERRO, mesmo fluxo do webhook). */
class EventosAdminTest extends PagamentoAdminBase {

	@Autowired ProcessamentoDePagamento processamento;

	@Test
	void anonimoRecebe401EClienteRecebe403() throws Exception {
		var id = evento("1", "ERRO", "mp_indisponivel");
		var anonimo = new me.clama.conta.web.Navegador(porta);
		anonimo.get("/api/auth/eu");
		for (var nav : List.of(anonimo, entrar(Papel.CLIENTE))) {
			var esperado = nav == anonimo ? 401 : 403;
			assertThat(nav.get("/api/admin/eventos-mp").statusCode()).isEqualTo(esperado);
			assertThat(nav.get("/api/admin/eventos-mp/" + id).statusCode()).isEqualTo(esperado);
			assertThat(nav.post("/api/admin/eventos-mp/" + id + "/reprocessamento", "{}").statusCode()).isEqualTo(esperado);
		}
		assertThat(MP.getAllServeEvents()).isEmpty();
	}

	@Test
	void listaPaginadaComFiltroDeStatusEDetalheComPayload() throws Exception {
		var a = evento("1", "ERRO", "mp_indisponivel");
		var b = evento("2", "PROCESSADO", null);
		var adm = entrar(Papel.ADMIN);
		var res = adm.get("/api/admin/eventos-mp");
		assertThat(json(res).get("page").get("totalElements").asInt()).isEqualTo(2);
		assertThat(json(res).get("page").get("size").asInt()).isEqualTo(20);
		assertThat(ids(res)).containsExactly(String.valueOf(b), String.valueOf(a));
		assertThat(ids(adm.get("/api/admin/eventos-mp?status=erro"))).containsExactly(String.valueOf(a));
		var item = json(res).get("content").get(1);
		assertThat(item.get("recebidoEm").asString()).endsWith("Z");
		assertThat(item.get("payload").isNull()).as("a lista nao traz payload").isTrue();

		var d = json(adm.get("/api/admin/eventos-mp/" + a));
		assertThat(d.get("payload").get("data").get("id").asString()).isEqualTo(String.valueOf(PAG));
		assertThat(d.get("motivo").asString()).isEqualTo("mp_indisponivel");
	}

	@Test
	void statusMalformadoE400ComCampoEInexistenteE404() throws Exception {
		var adm = entrar(Papel.ADMIN);
		var res = adm.get("/api/admin/eventos-mp?status=XYZ");
		assertThat(res.statusCode()).isEqualTo(400);
		assertThat(json(res).get("codigo").asString()).isEqualTo("dados_invalidos");
		assertThat(json(res).get("erros").get(0).get("campo").asString()).isEqualTo("status");
		assertThat(adm.get("/api/admin/eventos-mp/999999").statusCode()).isEqualTo(404);
		assertThat(adm.post("/api/admin/eventos-mp/999999/reprocessamento", "{}").statusCode()).isEqualTo(404);
	}

	@Test
	void reprocessaEventoComErroPeloMesmoFluxoDoWebhook() throws Exception {
		var pedido = pedido(3000, instituicao("Lar"));
		cobranca(pedido, 1, PAG);
		aprovado(pedido);
		var ev = evento("1", "ERRO", "mp_indisponivel");

		var adm = entrar(Papel.ADMIN);
		var res = adm.post("/api/admin/eventos-mp/" + ev + "/reprocessamento", "{}");

		assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
		assertThat(json(res).get("resultado").asString()).isEqualTo("ok");
		assertThat(json(res).get("evento").get("status").asString()).isEqualTo("PROCESSADO");
		assertThat(jdbc.queryForObject("select status from pedido where id = ?", String.class, pedido)).isEqualTo("PAGO");
		assertThat(contar("select count(*) from repasse")).isEqualTo(1);
		var diff = jdbc.queryForObject("select diff::text from auditoria where entidade = 'pagamento_evento' and entidade_id = ?",
				String.class, String.valueOf(ev));
		assertThat(diff).contains("ERRO").contains("PROCESSADO");
	}

	@Test
	void reprocessaEventoRecebido() throws Exception {
		var pedido = pedido(3000, null);
		cobranca(pedido, 1, PAG);
		aprovado(pedido);
		var ev = evento("1", "RECEBIDO", null);
		var res = entrar(Papel.ADMIN).post("/api/admin/eventos-mp/" + ev + "/reprocessamento", "{}");
		assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
		assertThat(json(res).get("evento").get("status").asString()).isEqualTo("PROCESSADO");
	}

	@Test
	void eventoTerminalE409SemTocarNoMpNemAuditar() throws Exception {
		var adm = entrar(Papel.ADMIN);
		for (var status : List.of("PROCESSADO", "IGNORADO")) {
			var ev = evento("t-" + status, status, status.equals("IGNORADO") ? "pagamento_orfao" : null);
			var res = adm.post("/api/admin/eventos-mp/" + ev + "/reprocessamento", "{}");
			assertThat(res.statusCode()).as(status).isEqualTo(409);
			assertThat(json(res).get("codigo").asString()).isEqualTo("evento_encerrado");
		}
		assertThat(MP.getAllServeEvents()).isEmpty();
		assertThat(contar("select count(*) from auditoria where entidade = 'pagamento_evento'")).isZero();
	}

	@Test
	void mpForaDoArVira502EOEventoFicaEmErro() throws Exception {
		MP.stubFor(get("/v1/payments/" + PAG).willReturn(serviceUnavailable()));
		var ev = evento("1", "RECEBIDO", null);
		var res = entrar(Papel.ADMIN).post("/api/admin/eventos-mp/" + ev + "/reprocessamento", "{}");
		assertThat(res.statusCode()).isEqualTo(502);
		assertThat(json(res).get("codigo").asString()).isEqualTo("mp_indisponivel");
		assertThat(jdbc.queryForObject("select status from pagamento_evento where id = ?", String.class, ev)).isEqualTo("ERRO");
		assertThat(contar("select count(*) from auditoria where entidade = 'pagamento_evento'")).isEqualTo(1);
	}

	@Test
	void reprocessamentoEmCorridaComReentregaProcessaUmaVezSo() throws Exception {
		var pedido = pedido(3000, instituicao("Lar"));
		cobranca(pedido, 1, PAG);
		MP.stubFor(get("/v1/payments/" + PAG).willReturn(okJson("""
				{"id": %d, "status": "approved", "external_reference": "%s", "transaction_amount": 30.00, "currency_id": "BRL"}"""
				.formatted(PAG, pedido)).withFixedDelay(300)));
		var ev = evento("1", "ERRO", "mp_indisponivel");
		var adm = entrar(Papel.ADMIN);
		var largada = new CountDownLatch(1);
		try (var threads = Executors.newFixedThreadPool(2)) {
			var admin = threads.submit((Callable<Integer>) () -> {
				largada.await();
				return adm.post("/api/admin/eventos-mp/" + ev + "/reprocessamento", "{}").statusCode();
			});
			var reentrega = threads.submit((Callable<ProcessamentoDePagamento.Resultado>) () -> {
				largada.await();
				return processamento.receber("1", "payment", String.valueOf(PAG), "{\"id\":\"1\"}");
			});
			largada.countDown();
			assertThat(admin.get()).isEqualTo(200);
			assertThat(reentrega.get()).isIn(ProcessamentoDePagamento.Resultado.OK, ProcessamentoDePagamento.Resultado.JA_PROCESSADO,
					ProcessamentoDePagamento.Resultado.JA_PAGO);
		}
		assertThat(jdbc.queryForObject("select status from pedido where id = ?", String.class, pedido)).isEqualTo("PAGO");
		assertThat(contar("select count(*) from repasse")).isEqualTo(1);
		assertThat(contar("select count(*) from usuario where email = 'maria@exemplo.com'")).isEqualTo(1);
		assertThat(contar("select count(*) from pagamento_evento where status = 'PROCESSADO'")).isEqualTo(1);
		assertThat(contar("select count(*) from cobranca where paga_em is not null")).isEqualTo(1);
	}
}
