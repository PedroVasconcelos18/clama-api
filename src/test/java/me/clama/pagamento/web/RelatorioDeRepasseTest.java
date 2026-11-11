package me.clama.pagamento.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import me.clama.conta.interno.Papel;
import me.clama.conta.web.Navegador;
import me.clama.pagamento.interno.relatorio.Ciclo;
import org.junit.jupiter.api.Test;

/** Relatorio de repasse da instituicao: previa JSON e CSV (D3, D4, INV:153-155), sem PII alem do nome abreviado. */
class RelatorioDeRepasseTest extends PagamentoAdminBase {

	static final String CICLO = "de=2026-10-28&ate=2026-11-27";

	/** Dentro do ciclo 28/10 a 27/11 (SP): pontas inclusivas; fora: um segundo antes e um segundo depois. */
	Long semear() {
		var lar = instituicao("Lar Esperança");
		repasseEm(pedido("Maria Silva Santos", 3000, lar), lar, "REGISTRADO", "2026-10-28T03:00:00Z"); // 00:00:00 de 28/10 em SP
		repasseEm(pedido("Ágata Éfrem álvares", 3000, lar), lar, "TRANSFERIDO", "2026-11-28T02:59:59Z"); // 23:59:59 de 27/11 em SP
		repasseEm(pedido("Fora Antes", 3000, lar), lar, "REGISTRADO", "2026-10-28T02:59:59Z"); // 23:59:59 de 27/10
		repasseEm(pedido("Fora Depois", 3000, lar), lar, "REGISTRADO", "2026-11-28T03:00:00Z"); // 00:00:00 de 28/11
		repasseEm(pedido("Estornada Silva", 3000, lar), lar, "CANCELADO", "2026-11-10T15:00:00Z");
		var outra = instituicao("Outra");
		repasseEm(pedido("De Outra", 3000, outra), outra, "REGISTRADO", "2026-11-10T15:00:00Z");
		return lar;
	}

	@Test
	void anonimoRecebe401EClienteRecebe403() throws Exception {
		var lar = semear();
		var anonimo = new Navegador(porta);
		anonimo.get("/api/auth/eu");
		for (var nav : List.of(anonimo, entrar(Papel.CLIENTE))) {
			var esperado = nav == anonimo ? 401 : 403;
			for (var rota : List.of("relatorio", "relatorio.csv", "relatorio-interno.csv")) {
				assertThat(nav.get("/api/admin/repasses/" + rota + "?instituicaoId=" + lar + "&" + CICLO).statusCode()).as(rota).isEqualTo(esperado);
			}
			assertThat(nav.get("/api/admin/repasses/auditoria").statusCode()).isEqualTo(esperado);
		}
		assertThat(contar("select count(*) from auditoria where entidade = 'relatorio_interno'")).isZero();
	}

	@Test
	void previaTemNomeAbreviadoReferenciaOrdemEBordasInclusivasSemCancelado() throws Exception {
		var lar = semear();
		var res = entrar(Papel.ADMIN).get("/api/admin/repasses/relatorio?instituicaoId=" + lar + "&" + CICLO);
		assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
		var j = json(res);
		assertThat(j.get("instituicao").asString()).isEqualTo("Lar Esperança");
		assertThat(j.get("de").asString()).isEqualTo("2026-10-28");
		assertThat(j.get("ate").asString()).isEqualTo("2026-11-27");
		assertThat(j.get("linhas")).hasSize(2);
		var a = j.get("linhas").get(0);
		var b = j.get("linhas").get(1);
		assertThat(a.get("doador").asString()).isEqualTo("Maria S.");
		assertThat(b.get("doador").asString()).isEqualTo("Ágata Á."); // acento preservado
		assertThat(a.get("data").asString()).isEqualTo("2026-10-28");
		assertThat(b.get("data").asString()).isEqualTo("2026-11-27");
		assertThat(a.get("referencia").asString()).matches("#[0-9a-f]{4}…[0-9a-f]{4}");
		assertThat(a.get("valorDoadoCentavos").asInt()).isEqualTo(3000);
		assertThat(a.get("percentual").asInt()).isEqualTo(20);
		assertThat(a.get("valorRepasseCentavos").asInt()).isEqualTo(600);
		assertThat(a.get("status").asString()).isEqualTo("REGISTRADO");
		assertThat(b.get("status").asString()).isEqualTo("TRANSFERIDO");
		assertThat(new java.util.LinkedHashSet<String>(a.propertyNames())).containsExactlyInAnyOrder("doador", "referencia", "data",
				"valorDoadoCentavos", "percentual", "valorRepasseCentavos", "status");
		assertThat(j.get("quantidade").asInt()).isEqualTo(2);
		assertThat(j.get("totalDoadoCentavos").asInt()).isEqualTo(6000);
		assertThat(j.get("totalRepasseCentavos").asInt()).isEqualTo(1200);
		assertThat(j.get("pendentes").asInt()).isEqualTo(1);
		assertThat(j.get("totalPendenteCentavos").asInt()).isEqualTo(600);
		assertThat(res.body()).doesNotContain("Silva", "Éfrem", "álvares", "maria@", "52998224725", "11987654321", "Pela família", "Estornada");
	}

	@Test
	void ordemDesempataPeloId() throws Exception {
		var lar = instituicao("Lar");
		var primeiro = repasseEm(pedido("Ana Um", 3000, lar), lar, "REGISTRADO", "2026-11-01T12:00:00Z");
		var segundo = repasseEm(pedido("Bia Dois", 3000, lar), lar, "REGISTRADO", "2026-11-01T12:00:00Z");
		var antes = repasseEm(pedido("Caio Tres", 3000, lar), lar, "REGISTRADO", "2026-10-30T12:00:00Z");
		assertThat(primeiro).isLessThan(segundo);
		var linhas = json(entrar(Papel.ADMIN).get("/api/admin/repasses/relatorio?instituicaoId=" + lar + "&" + CICLO)).get("linhas");
		assertThat(List.of(linhas.get(0).get("doador").asString(), linhas.get(1).get("doador").asString(), linhas.get(2).get("doador").asString()))
				.containsExactly("Caio T.", "Ana U.", "Bia D.");
		assertThat(antes).isNotNull();
	}

	@Test
	void nomeUnicoEVazio() throws Exception {
		var lar = instituicao("Lar");
		repasseEm(pedido("Madalena", 3000, lar), lar, "REGISTRADO", "2026-11-01T12:00:00Z");
		repasseEm(pedido("", 3000, lar), lar, "REGISTRADO", "2026-11-02T12:00:00Z");
		var linhas = json(entrar(Papel.ADMIN).get("/api/admin/repasses/relatorio?instituicaoId=" + lar + "&" + CICLO)).get("linhas");
		assertThat(linhas.get(0).get("doador").asString()).isEqualTo("Madalena");
		assertThat(linhas.get(1).get("doador").asString()).isEqualTo("(sem nome)");
	}

	@Test
	void mesDatasECicloPadrao() throws Exception {
		var lar = semear();
		var adm = entrar(Papel.ADMIN);
		var fev = json(adm.get("/api/admin/repasses/relatorio?instituicaoId=" + lar + "&mes=2026-02"));
		assertThat(fev.get("de").asString()).isEqualTo("2026-02-01");
		assertThat(fev.get("ate").asString()).isEqualTo("2026-02-28");
		assertThat(fev.get("quantidade").asInt()).isZero();
		var dez = json(adm.get("/api/admin/repasses/relatorio?instituicaoId=" + lar + "&mes=2026-12"));
		assertThat(dez.get("ate").asString()).isEqualTo("2026-12-31");
		var nov = json(adm.get("/api/admin/repasses/relatorio?instituicaoId=" + lar + "&mes=2026-11"));
		assertThat(nov.get("quantidade").asInt()).isEqualTo(2); // 27/11 23:59:59 e 28/11 00:00:00: o mes vai de 1 a 30
		var padrao = json(adm.get("/api/admin/repasses/relatorio?instituicaoId=" + lar));
		var esperado = Ciclo.fechadoEm(LocalDate.now(ZoneId.of("America/Sao_Paulo")));
		assertThat(padrao.get("de").asString()).isEqualTo(esperado.de().toString());
		assertThat(padrao.get("ate").asString()).isEqualTo(esperado.ate().toString());
	}

	@Test
	void periodoMalformadoE400ComOCampo() throws Exception {
		var lar = instituicao("Lar");
		var adm = entrar(Papel.ADMIN);
		var casos = List.of(
				new String[] { "mes=2026-02&de=2026-02-01&ate=2026-02-10", "mes" },
				new String[] { "mes=2026-02&de=2026-02-01", "mes" },
				new String[] { "de=2026-02-01", "ate" },
				new String[] { "ate=2026-02-01", "de" },
				new String[] { "de=2026-03-02&ate=2026-03-01", "de" },
				new String[] { "mes=2026-13", "mes" },
				new String[] { "de=01/03/2026&ate=02/03/2026", "de" });
		for (var c : casos) {
			var res = adm.get("/api/admin/repasses/relatorio?instituicaoId=" + lar + "&" + c[0]);
			assertThat(res.statusCode()).as(c[0]).isEqualTo(400);
			assertThat(json(res).get("codigo").asString()).isEqualTo("dados_invalidos");
			assertThat(json(res).get("erros").get(0).get("campo").asString()).as(c[0]).isEqualTo(c[1]);
		}
		for (var rota : List.of("relatorio.csv", "relatorio-interno.csv")) { // o 400 vale para os tres
			assertThat(adm.get("/api/admin/repasses/" + rota + "?instituicaoId=" + lar + "&de=2026-02-01").statusCode()).isEqualTo(400);
		}
		assertThat(contar("select count(*) from auditoria where entidade = 'relatorio_interno'")).isZero();
	}

	/** {@code @Size} no parametro e imposto pela validacao de metodo do Spring (sem {@code @Validated}): estourar e 400, nao 500. */
	@Test
	void parametroGrandeDemaisE400NaoE500() throws Exception {
		var lar = instituicao("Lar");
		var adm = entrar(Papel.ADMIN);
		for (var rota : List.of("relatorio", "relatorio.csv", "relatorio-interno.csv")) {
			for (var param : List.of("mes=" + "2".repeat(8), "de=" + "2".repeat(11), "ate=" + "2".repeat(11))) {
				var res = adm.get("/api/admin/repasses/" + rota + "?instituicaoId=" + lar + "&" + param);
				assertThat(res.statusCode()).as(rota + " " + param).isEqualTo(400);
				assertThat(json(res).get("codigo").asString()).as(rota + " " + param).isEqualTo("requisicao_invalida"); // do @Size, nao do parse
			}
			assertThat(adm.get("/api/admin/repasses/" + rota + "?instituicaoId=" + "9".repeat(21)).statusCode()).as(rota).isEqualTo(400);
		}
		assertThat(contar("select count(*) from auditoria where entidade = 'relatorio_interno'")).isZero();
	}

	@Test
	void instituicaoAusenteE400EInexistenteE404() throws Exception {
		var adm = entrar(Papel.ADMIN);
		var sem = adm.get("/api/admin/repasses/relatorio?" + CICLO);
		assertThat(sem.statusCode()).isEqualTo(400);
		assertThat(json(sem).get("erros").get(0).get("campo").asString()).isEqualTo("instituicaoId");
		assertThat(adm.get("/api/admin/repasses/relatorio?instituicaoId=abc&" + CICLO).statusCode()).isEqualTo(400);
		var nada = adm.get("/api/admin/repasses/relatorio?instituicaoId=999999&" + CICLO);
		assertThat(nada.statusCode()).isEqualTo(404);
		assertThat(adm.get("/api/admin/repasses/relatorio.csv?instituicaoId=999999&" + CICLO).statusCode()).isEqualTo(404);
	}

	@Test
	void instituicaoInativaTambemTemRelatorio() throws Exception {
		var lar = semear();
		jdbc.update("update instituicao set ativo = false where id = ?", lar);
		var res = entrar(Papel.ADMIN).get("/api/admin/repasses/relatorio?instituicaoId=" + lar + "&" + CICLO);
		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(json(res).get("quantidade").asInt()).isEqualTo(2);
	}

	@Test
	void periodoSemRepasseGeraORelatorioZerado() throws Exception {
		var lar = instituicao("Pela Vida");
		var adm = entrar(Papel.ADMIN);
		var j = json(adm.get("/api/admin/repasses/relatorio?instituicaoId=" + lar + "&" + CICLO));
		assertThat(j.get("linhas")).isEmpty();
		assertThat(j.get("quantidade").asInt()).isZero();
		assertThat(j.get("totalRepasseCentavos").asInt()).isZero();
		assertThat(j.get("pendentes").asInt()).isZero();
		var csv = adm.get("/api/admin/repasses/relatorio.csv?instituicaoId=" + lar + "&" + CICLO);
		assertThat(csv.statusCode()).isEqualTo(200);
		assertThat(csv.body()).isEqualTo("﻿Relatório de repasse — Pela Vida\r\nPeríodo: 28/10/2026 a 27/11/2026\r\n\r\n"
				+ "Doador;Referência do pedido;Data da doação;Valor doado (R$);Percentual de repasse;Valor a receber (R$)\r\n\r\n"
				+ "TOTAL (0 doações);;;0,00;;0,00\r\n");
	}

	@Test
	void csvDaInstituicaoNuncaTemEmailTelefoneCpfOracaoNemNomeInteiro() throws Exception {
		var lar = semear();
		var res = entrar(Papel.ADMIN).get("/api/admin/repasses/relatorio.csv?instituicaoId=" + lar + "&" + CICLO);
		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(res.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/csv").containsIgnoringCase("utf-8");
		assertThat(res.headers().firstValue("Content-Disposition").orElseThrow())
				.contains("attachment").contains("filename=\"relatorio-repasse-lar-esperanca-2026-10-28_a_2026-11-27.csv\"");
		var corpo = res.body();
		assertThat(corpo).startsWith("﻿Relatório de repasse — Lar Esperança\r\nPeríodo: 28/10/2026 a 27/11/2026\r\n\r\n");
		assertThat(corpo).contains("Maria S.;#", "Ágata Á.;#", ";28/10/2026;30,00;20%;6,00\r\n", ";27/11/2026;30,00;20%;6,00\r\n")
				.endsWith("\r\n\r\nTOTAL (2 doações);;;60,00;;12,00\r\n");
		assertThat(corpo.replace("\r\n", "")).doesNotContain("\n").doesNotContain("\r");
		assertThat(corpo).doesNotContain("@", "52998224725", "529.982", "11987654321", "Pela família", "Silva", "Santos", "Éfrem", "álvares",
				"Estornada", "Fora");
		assertThat(corpo.getBytes(StandardCharsets.UTF_8)).startsWith(0xEF, 0xBB, 0xBF);
	}

	@Test
	void umaDoacaoNoSingularENomeDaInstituicaoComPontoEVirgulaEntreAspas() throws Exception {
		var inst = instituicao("Lar; Esperança");
		repasseEm(pedido("Maria Silva Santos", 3000, inst), inst, "REGISTRADO", "2026-11-01T12:00:00Z");
		var res = entrar(Papel.ADMIN).get("/api/admin/repasses/relatorio.csv?instituicaoId=" + inst + "&" + CICLO);
		assertThat(res.body()).startsWith("﻿\"Relatório de repasse — Lar; Esperança\"\r\n").endsWith("TOTAL (1 doação);;;30,00;;6,00\r\n");
		assertThat(res.headers().firstValue("Content-Disposition").orElseThrow()).contains("relatorio-repasse-lar-esperanca-");
	}
}
