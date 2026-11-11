package me.clama.pagamento.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import me.clama.conta.interno.Papel;
import me.clama.conta.web.Navegador;
import org.junit.jupiter.api.Test;

/** Admin de repasses: lista paginada sem PII, filtros e "marcar transferido". */
class RepassesAdminTest extends PagamentoAdminBase {

	@Test
	void anonimoRecebe401EClienteRecebe403() throws Exception {
		var r = repasse(pedido(3000, instituicao("Lar")), instituicao("Outro"), "REGISTRADO");
		var anonimo = new Navegador(porta);
		anonimo.get("/api/auth/eu");
		for (var nav : List.of(anonimo, entrar(Papel.CLIENTE))) {
			var esperado = nav == anonimo ? 401 : 403;
			assertThat(nav.get("/api/admin/repasses").statusCode()).isEqualTo(esperado);
			assertThat(nav.post("/api/admin/repasses/" + r + "/transferencia", "{}").statusCode()).isEqualTo(esperado);
		}
		assertThat(jdbc.queryForObject("select status from repasse where id = ?", String.class, r)).isEqualTo("REGISTRADO");
	}

	@Test
	void listaPagedModelSemPiiComNomeDaInstituicao() throws Exception {
		var lar = instituicao("Lar Esperança");
		var p = pedido(3000, lar);
		var r = repasse(p, lar, "REGISTRADO");
		var res = entrar(Papel.ADMIN).get("/api/admin/repasses");
		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(json(res).get("page").get("size").asInt()).isEqualTo(20);
		assertThat(ids(res)).containsExactly(String.valueOf(r));
		var item = json(res).get("content").get(0);
		assertThat(new java.util.LinkedHashSet<String>(item.propertyNames())).containsExactlyInAnyOrder("id", "pedidoId", "instituicaoId",
				"instituicao", "valorBaseCentavos", "percentual", "valorCentavos", "status", "criadoEm", "transferidoEm", "canceladoEm",
				"motivo");
		assertThat(item.get("pedidoId").asString()).isEqualTo(p.toString());
		assertThat(item.get("instituicao").asString()).isEqualTo("Lar Esperança");
		assertThat(item.get("valorCentavos").asInt()).isEqualTo(600);
		assertThat(item.get("criadoEm").asString()).endsWith("Z");
		assertThat(res.body()).doesNotContain("Maria", "maria@", "52998224725", "Pela família");
	}

	@Test
	void filtrosPorInstituicaoStatusEPeriodo() throws Exception {
		var lar = instituicao("Lar");
		var mar = instituicao("Mar");
		var a = repasse(pedido(3000, lar), lar, "REGISTRADO");
		var b = repasse(pedido(3000, mar), mar, "TRANSFERIDO");
		var c = repasse(pedido(3000, mar), mar, "CANCELADO");
		jdbc.update("update repasse set criado_em = '2026-03-10T02:59:59Z' where id = ?", a); // dia 9 em SP
		var adm = entrar(Papel.ADMIN);
		assertThat(ids(adm.get("/api/admin/repasses?instituicaoId=" + lar))).containsExactly(String.valueOf(a));
		assertThat(ids(adm.get("/api/admin/repasses?status=transferido"))).containsExactly(String.valueOf(b));
		assertThat(ids(adm.get("/api/admin/repasses?status=CANCELADO&instituicaoId=" + mar))).containsExactly(String.valueOf(c));
		assertThat(ids(adm.get("/api/admin/repasses?ate=2026-03-09"))).containsExactly(String.valueOf(a));
		assertThat(ids(adm.get("/api/admin/repasses?de=2026-03-10"))).containsExactlyInAnyOrder(String.valueOf(b), String.valueOf(c));
	}

	@Test
	void filtroMalformadoE400ComCampo() throws Exception {
		var adm = entrar(Papel.ADMIN);
		for (var par : List.of("status=XYZ", "instituicaoId=abc", "de=ontem", "ate=1/2/2026")) {
			var res = adm.get("/api/admin/repasses?" + par);
			assertThat(res.statusCode()).as(par).isEqualTo(400);
			assertThat(json(res).get("codigo").asString()).isEqualTo("dados_invalidos");
			assertThat(json(res).get("erros").get(0).get("campo").asString()).isEqualTo(par.substring(0, par.indexOf('=')));
		}
	}

	@Test
	void transferenciaMarcaRegistradoEAudita() throws Exception {
		var lar = instituicao("Lar");
		var r = repasse(pedido(3000, lar), lar, "REGISTRADO");
		var res = entrar(Papel.ADMIN).post("/api/admin/repasses/" + r + "/transferencia", "{}");
		assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
		assertThat(json(res).get("status").asString()).isEqualTo("TRANSFERIDO");
		assertThat(json(res).get("transferidoEm").asString()).endsWith("Z");
		assertThat(jdbc.queryForObject("select status from repasse where id = ?", String.class, r)).isEqualTo("TRANSFERIDO");
		assertThat(contar("select count(*) from auditoria where entidade = 'repasse' and entidade_id = ?", String.valueOf(r))).isEqualTo(1);
	}

	@Test
	void transferirDeNovoENoOpSemNovaAuditoria() throws Exception {
		var lar = instituicao("Lar");
		var r = repasse(pedido(3000, lar), lar, "REGISTRADO");
		var adm = entrar(Papel.ADMIN);
		var primeira = json(adm.post("/api/admin/repasses/" + r + "/transferencia", "{}")).get("transferidoEm").asString();
		var segunda = adm.post("/api/admin/repasses/" + r + "/transferencia", "{}");
		assertThat(segunda.statusCode()).isEqualTo(200);
		assertThat(json(segunda).get("transferidoEm").asString()).isEqualTo(primeira);
		assertThat(contar("select count(*) from auditoria where entidade = 'repasse'")).isEqualTo(1);
	}

	@Test
	void canceladoE409EInexistenteE404() throws Exception {
		var lar = instituicao("Lar");
		var r = repasse(pedido(3000, lar), lar, "CANCELADO");
		var adm = entrar(Papel.ADMIN);
		var res = adm.post("/api/admin/repasses/" + r + "/transferencia", "{}");
		assertThat(res.statusCode()).isEqualTo(409);
		assertThat(json(res).get("codigo").asString()).isEqualTo("repasse_cancelado");
		assertThat(jdbc.queryForObject("select status from repasse where id = ?", String.class, r)).isEqualTo("CANCELADO");
		assertThat(contar("select count(*) from auditoria where entidade = 'repasse'")).isZero();
		assertThat(adm.post("/api/admin/repasses/999999/transferencia", "{}").statusCode()).isEqualTo(404);
	}
}
