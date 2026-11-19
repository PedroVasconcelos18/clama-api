package me.clama.pedido.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import me.clama.conta.interno.Papel;
import me.clama.conta.web.Navegador;
import me.clama.infra.cripto.Cifra;
import me.clama.pedido.interno.Pedido;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Admin de pedidos: lista com filtros e busca por HMAC, detalhe sem CPF e sem Pix, PATCH so de dados com auditoria sem valores. */
class PedidosAdminTest extends PedidoWebTeste {

	@BeforeEach
	@AfterEach
	void limparAuditoria() {
		jdbc.update("delete from auditoria where entidade in ('pedido')");
	}

	@MockitoSpyBean Cifra cifra;

	Navegador admin() throws Exception {
		var email = "ped-adm-" + UUID.randomUUID() + "@exemplo.com";
		Navegador.usuario(usuarios, encoder, email, "senha-forte-123", Papel.ADMIN);
		var nav = new Navegador(porta);
		assertThat(nav.login(email, "senha-forte-123").statusCode()).isEqualTo(200);
		return nav;
	}

	Pedido pedido(Long usuario, Long plano, Long instituicao, String nome, String email, String cpf, Instant em) {
		var d = new Pedido.Dados(usuario, plano, instituicao, 3000, nome, email, cpf, "11999990000", (short) 40, "feminino",
				"Pela minha família", em, null, "1", null);
		return salvar(Pedido.novo(d, hmac, em));
	}

	List<String> ids(HttpResponse<String> res) {
		assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
		var l = new ArrayList<String>();
		for (var n : corpo(res).get("content")) l.add(n.get("id").asString());
		return l;
	}

	// --- 401 / 403 ---

	@Test
	void anonimoRecebe401EClienteRecebe403() throws Exception {
		var p = pedido(null, plano("Simples", 3000), null, "Maria", "maria@exemplo.com", "52998224725", relogio.instant());
		var anonimo = anonimo();
		var cliente = logado().nav();
		for (var nav : List.of(anonimo, cliente)) {
			var esperado = nav == anonimo ? 401 : 403;
			assertThat(nav.get("/api/admin/pedidos").statusCode()).isEqualTo(esperado);
			assertThat(nav.get("/api/admin/pedidos/" + p.getId()).statusCode()).isEqualTo(esperado);
			assertThat(nav.patch("/api/admin/pedidos/" + p.getId(), "{\"nome\":\"X Y\"}").statusCode()).isEqualTo(esperado);
		}
	}

	// --- lista ---

	@Test
	void listaPaginadaComoPagedModelEItemSemPiiAlemDeNomeEEmail() throws Exception {
		var plano = plano("Simples", 3000);
		var antigo = pedido(null, plano, null, "Maria Souza", "maria@exemplo.com", "52998224725", Instant.parse("2026-03-10T12:00:00Z"));
		var novo = pedido(null, plano, null, "João Lima", "joao@exemplo.com", "11144477735", Instant.parse("2026-03-11T12:00:00Z"));
		var res = admin().get("/api/admin/pedidos");
		assertThat(res.statusCode()).isEqualTo(200);
		var json = corpo(res);
		assertThat(json.get("page").get("size").asInt()).isEqualTo(20);
		assertThat(json.get("page").get("totalElements").asInt()).isEqualTo(2);
		assertThat(ids(res)).containsExactly(novo.getId().toString(), antigo.getId().toString());
		var item = json.get("content").get(0);
		assertThat(chaves(item)).containsExactlyInAnyOrder("id", "status", "valorCentavos", "plano", "criadoEm", "gratuito", "nome", "email");
		assertThat(item.get("email").asString()).isEqualTo("joao@exemplo.com");
		assertThat(item.get("nome").asString()).isEqualTo("João Lima");
		assertThat(item.get("plano").asString()).isEqualTo("Simples");
		assertThat(item.get("criadoEm").asString()).isEqualTo("2026-03-11T12:00:00Z");
		assertThat(res.body()).doesNotContain("11144477735").doesNotContain("11999990000").doesNotContain("Pela minha");
	}

	@Test
	void listaSoDecifraNomeEEmailDeCadaLinha() throws Exception {
		var plano = plano("Simples", 3000);
		pedido(null, plano, null, "Maria Souza", "maria@exemplo.com", "52998224725", relogio.instant());
		pedido(null, plano, null, "João Lima", "joao@exemplo.com", "11144477735", relogio.instant().minusSeconds(5));
		var adm = admin();
		clearInvocations(cifra);
		assertThat(adm.get("/api/admin/pedidos").statusCode()).isEqualTo(200);
		verify(cifra, times(4)).decifrar(anyString()); // 2 pedidos x (nome, email); CPF, telefone, pedido e oracao ficam cifrados
	}

	@Test
	void buscaPorIdPrefixoParcialCompletoEOutroPedido() throws Exception {
		var plano = plano("Simples", 3000);
		var a = pedido(null, plano, null, "Maria", "maria@exemplo.com", "52998224725", relogio.instant());
		var b = pedido(null, plano, null, "Joao", "joao@exemplo.com", "11144477735", relogio.instant().minusSeconds(5));
		var adm = admin();
		var ida = a.getId().toString();
		assertThat(ids(adm.get("/api/admin/pedidos?q=" + ida.substring(0, 8)))).containsExactly(ida);
		assertThat(ids(adm.get("/api/admin/pedidos?q=" + ida.substring(0, 13)))).as("prefixo com hifen").containsExactly(ida);
		assertThat(ids(adm.get("/api/admin/pedidos?q=" + ida.replace("-", "").substring(0, 15)))).as("sem hifens").containsExactly(ida);
		assertThat(ids(adm.get("/api/admin/pedidos?q=" + ida))).as("uuid completo").containsExactly(ida);
		assertThat(ids(adm.get("/api/admin/pedidos?q=" + ida.replace("-", "")))).containsExactly(ida);
		assertThat(ids(adm.get("/api/admin/pedidos?q=" + b.getId().toString().substring(0, 8)))).doesNotContain(ida);
	}

	@Test
	void tamanhoDePaginaTemTetoDe100() throws Exception {
		pedido(null, plano("Simples", 3000), null, "Maria", "maria@exemplo.com", "52998224725", relogio.instant());
		assertThat(corpo(admin().get("/api/admin/pedidos?size=500")).get("page").get("size").asInt()).isEqualTo(100);
	}

	@Test
	void filtrosDeStatusDiaGratuitoEUsuario() throws Exception {
		var u = logado();
		var plano = plano("Simples", 3000);
		var a = pedido(u.id(), plano, null, "Maria", "maria@exemplo.com", "52998224725", Instant.parse("2026-03-10T02:59:59Z")); // dia 9 em SP
		var b = pedido(null, plano, null, "Maria", "maria@exemplo.com", "52998224725", Instant.parse("2026-03-10T03:00:00Z")); // dia 10
		b.concederGratuidade(relogio.instant());
		salvar(b);
		var c = pedido(null, plano, null, "Maria", "maria@exemplo.com", "52998224725", Instant.parse("2026-03-11T02:59:59Z")); // dia 10
		c.confirmarPagamento(relogio.instant());
		c.falhar("falha_envio", relogio.instant());
		salvar(c);
		var adm = admin();
		assertThat(ids(adm.get("/api/admin/pedidos?de=2026-03-10&ate=2026-03-10"))).containsExactly(c.getId().toString(), b.getId().toString());
		assertThat(ids(adm.get("/api/admin/pedidos?ate=2026-03-09"))).containsExactly(a.getId().toString());
		assertThat(ids(adm.get("/api/admin/pedidos?status=ERRO"))).containsExactly(c.getId().toString());
		assertThat(ids(adm.get("/api/admin/pedidos?status=erro,pago"))).containsExactly(c.getId().toString(), b.getId().toString());
		assertThat(ids(adm.get("/api/admin/pedidos?gratuito=true"))).containsExactly(b.getId().toString());
		assertThat(ids(adm.get("/api/admin/pedidos?gratuito=false"))).containsExactly(c.getId().toString(), a.getId().toString());
		assertThat(ids(adm.get("/api/admin/pedidos?usuarioId=" + u.id()))).containsExactly(a.getId().toString());
	}

	@Test
	void buscaPorEmailExatoCpfEPrefixoDoId() throws Exception {
		var plano = plano("Simples", 3000);
		var maria = pedido(null, plano, null, "Maria Souza", "Maria.Souza@Exemplo.com", "529.982.247-25", relogio.instant());
		var joao = pedido(null, plano, null, "João Lima", "joao@exemplo.com", "11144477735", relogio.instant().minusSeconds(5));
		var adm = admin();
		assertThat(ids(adm.get("/api/admin/pedidos?q=maria.souza@exemplo.com"))).containsExactly(maria.getId().toString());
		assertThat(ids(adm.get("/api/admin/pedidos?q=maria.souza@exemplo"))).as("e-mail so exato").isEmpty();
		assertThat(ids(adm.get("/api/admin/pedidos?q=52998224725"))).containsExactly(maria.getId().toString());
		assertThat(ids(adm.get("/api/admin/pedidos?q=529.982.247-25"))).containsExactly(maria.getId().toString());
		assertThat(ids(adm.get("/api/admin/pedidos?q=" + joao.getId().toString().substring(0, 8)))).containsExactly(joao.getId().toString());
		assertThat(ids(adm.get("/api/admin/pedidos?q=" + joao.getId().toString().substring(0, 8).toUpperCase()))).containsExactly(joao.getId().toString());
	}

	@Test
	void buscaPorNomeNaoExisteECuringa() throws Exception {
		pedido(null, plano("Simples", 3000), null, "Maria Souza", "maria@exemplo.com", "52998224725", relogio.instant());
		var res = admin().get("/api/admin/pedidos?q=Maria%20Souza");
		assertThat(res.statusCode()).isEqualTo(400);
		assertThat(corpo(res).get("codigo").asString()).isEqualTo("dados_invalidos");
		assertThat(erroDe(res, "q")).contains("e-mail exato");
		assertThat(admin().get("/api/admin/pedidos?q=%25").statusCode()).as("% nao e curinga").isEqualTo(400);
	}

	@Test
	void filtroMalformadoE400ComCampo() throws Exception {
		var adm = admin();
		for (var par : List.of("status=XYZ", "de=ontem", "ate=31-12-2026", "gratuito=talvez", "usuarioId=abc")) {
			var res = adm.get("/api/admin/pedidos?" + par);
			assertThat(res.statusCode()).as(par).isEqualTo(400);
			assertThat(corpo(res).get("codigo").asString()).isEqualTo("dados_invalidos");
			assertThat(erroDe(res, par.substring(0, par.indexOf('=')))).as(par).isNotNull();
		}
		var invertido = adm.get("/api/admin/pedidos?de=2026-03-11&ate=2026-03-10");
		assertThat(invertido.statusCode()).isEqualTo(400);
		assertThat(erroDe(invertido, "de")).isNotNull();
	}

	// --- detalhe ---

	@Test
	void detalheTemAsChavesDoMapaSemCpfNemPix() throws Exception {
		var plano = plano("Simples", 3000);
		var inst = instituicao("Lar Esperança", true);
		var p = pedido(null, plano, inst, "Maria Souza", "maria@exemplo.com", "52998224725", Instant.parse("2026-03-10T12:00:00Z"));
		jdbc.update("""
				insert into cobranca (pedido_id, n, mp_pagamento_id, pix_copia_e_cola, expira_em, criada_em, cancelada_em)
				values (?, 1, 111, '00020126-SEGREDO-PIX', now() + interval '1 day', now(), now())""", p.getId());
		jdbc.update("""
				insert into cobranca (pedido_id, n, mp_pagamento_id, pix_copia_e_cola, expira_em, criada_em)
				values (?, 2, 222, '00020126-SEGREDO-PIX', now() + interval '1 day', now())""", p.getId());
		jdbc.update("""
				insert into pagamento_evento (id_externo, tipo, status, payload, recebido_em, atualizado_em, pedido_id, motivo)
				values ('e1', 'payment', 'IGNORADO', '{"id":"e1"}', now(), now(), ?, 'valor_divergente')""", p.getId());
		jdbc.update("""
				insert into repasse (pedido_id, instituicao_id, valor_base_centavos, percentual, valor_centavos, status, criado_em)
				values (?, ?, 3000, 20, 600, 'REGISTRADO', now())""", p.getId(), inst);
		try {
			var res = admin().get("/api/admin/pedidos/" + p.getId());
			assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
			var d = corpo(res);
			assertThat(chaves(d)).containsExactlyInAnyOrder("id", "criadoEm", "atualizadoEm", "status", "gratuito", "valorCentavos",
					"usuarioId", "nome", "email", "telefone", "idade", "sexo", "pedidoOracao", "oracaoGerada", "plano", "instituicao",
					"tentativas", "ultimoErro", "risco", "tema", "cobrancas", "repasse", "eventos");
			assertThat(d.get("nome").asString()).isEqualTo("Maria Souza");
			assertThat(d.get("email").asString()).isEqualTo("maria@exemplo.com");
			assertThat(d.get("telefone").asString()).isEqualTo("11999990000");
			assertThat(d.get("criadoEm").asString()).isEqualTo("2026-03-10T12:00:00Z");
			assertThat(d.get("plano").get("nome").asString()).isEqualTo("Simples");
			assertThat(d.get("instituicao").get("nome").asString()).isEqualTo("Lar Esperança");
			assertThat(d.get("cobrancas").get(0).get("n").asInt()).isEqualTo(2);
			assertThat(d.get("cobrancas").get(0).get("status").asString()).isEqualTo("PENDENTE");
			assertThat(d.get("cobrancas").get(1).get("status").asString()).isEqualTo("CANCELADA");
			assertThat(d.get("repasse").get("status").asString()).isEqualTo("REGISTRADO");
			assertThat(d.get("repasse").get("valorCentavos").asInt()).isEqualTo(600);
			assertThat(d.get("eventos").get(0).get("motivo").asString()).isEqualTo("valor_divergente");
			assertThat(d.get("eventos").get(0).has("payload")).isFalse();
			assertThat(res.body()).doesNotContain("52998224725").doesNotContain("SEGREDO-PIX").doesNotContain("pix")
					.doesNotContain("cpf").doesNotContain("consent");
		} finally {
			jdbc.update("delete from repasse");
			jdbc.update("delete from pagamento_evento");
			jdbc.update("delete from cobranca");
		}
	}

	@Test
	void detalheDoAdminTrazRiscoETema() throws Exception {
		var p = pedido(null, plano("Simples", 3000), null, "Maria Souza", "maria@exemplo.com", "52998224725", Instant.now());
		var adm = admin();
		var antes = corpo(adm.get("/api/admin/pedidos/" + p.getId()));
		assertThat(antes.get("risco").isNull()).isTrue(); // ainda nao triado
		assertThat(antes.get("tema").isNull()).isTrue();

		jdbc.update("update pedido set risco = true, tema = 'luto' where id = ?", p.getId());

		var depois = corpo(adm.get("/api/admin/pedidos/" + p.getId()));
		assertThat(depois.get("risco").asBoolean()).isTrue();
		assertThat(depois.get("tema").asString()).isEqualTo("luto");
	}

	@Test
	void detalheInexistenteOuMalformadoE404() throws Exception {
		var adm = admin();
		assertThat(adm.get("/api/admin/pedidos/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
		assertThat(adm.get("/api/admin/pedidos/nao-e-uuid").statusCode()).isEqualTo(404);
	}

	// --- PATCH ---

	@Test
	void patchCorrigeDadosRecalculaOHmacEAuditaSoOsNomes() throws Exception {
		var p = pedido(null, plano("Simples", 3000), null, "Maria Souza", "maria@exemplo.com", "52998224725", relogio.instant());
		var adm = admin();
		var res = adm.patch("/api/admin/pedidos/" + p.getId(), """
				{"nome":"Maria de Souza","email":"Nova@Exemplo.com","telefone":"11888880000","idade":41,"sexo":"masculino",
				"pedidoOracao":"Por saúde","status":"ERRO","valorCentavos":1,"plano":99}""");
		assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
		var d = corpo(res);
		assertThat(d.get("nome").asString()).isEqualTo("Maria de Souza");
		assertThat(d.get("email").asString()).isEqualTo("Nova@Exemplo.com");
		assertThat(d.get("idade").asInt()).isEqualTo(41);
		assertThat(d.get("sexo").asString()).isEqualTo("masculino");
		assertThat(d.get("status").asString()).as("status nao se edita").isEqualTo("AGUARDANDO_PAGAMENTO");
		assertThat(d.get("valorCentavos").asInt()).isEqualTo(3000);

		var novo = pedidos.findById(p.getId()).orElseThrow();
		assertThat(novo.getEmailHmac()).isEqualTo(hmac.email("nova@exemplo.com")).isNotEqualTo(p.getEmailHmac());
		assertThat(novo.getPlanoId()).isEqualTo(p.getPlanoId());
		assertThat(ids(adm.get("/api/admin/pedidos?q=nova@exemplo.com"))).containsExactly(p.getId().toString());
		assertThat(ids(adm.get("/api/admin/pedidos?q=maria@exemplo.com"))).isEmpty();

		var diff = jdbc.queryForObject("select diff::text from auditoria where entidade = 'pedido' and entidade_id = ?", String.class,
				p.getId().toString());
		assertThat(Navegador.JSON.readTree(diff).get("campos").valueStream().map(JsonNode::asString).toList())
				.containsExactlyInAnyOrder("nome", "email", "telefone", "idade", "sexo", "pedidoOracao");
		assertThat(chaves(Navegador.JSON.readTree(diff))).containsExactly("campos");
		assertThat(diff).doesNotContain("Maria").doesNotContain("Nova").doesNotContain("saúde").doesNotContain("11888880000");
	}

	@Test
	void patchDoEmailDePedidoAguardandoConfirmacaoE409() throws Exception {
		var em = relogio.instant();
		var d = new Pedido.Dados(null, plano("Simples", 3000), null, 0, "Maria Souza", "maria@exemplo.com", "52998224725", "11999990000",
				(short) 40, "feminino", "Pela minha família", em, null, "1", null);
		var p = salvar(Pedido.freemium(d, hmac, em));
		var adm = admin();

		var res = adm.patch("/api/admin/pedidos/" + p.getId(), "{\"nome\":\"Maria de Souza\",\"email\":\"outra@exemplo.com\"}");
		assertThat(res.statusCode()).as(res.body()).isEqualTo(409);
		assertThat(corpo(res).get("codigo").asString()).isEqualTo("correcao_bloqueada");
		assertThat(corpo(res).get("mensagemPastoral").asString())
				.isEqualTo("Este pedido ainda aguarda a confirmação do e-mail. Peça para a pessoa fazer um novo pedido com o e-mail certo.");
		var igual = pedidos.findById(p.getId()).orElseThrow();
		assertThat(igual.getEmailHmac()).isEqualTo(p.getEmailHmac());
		assertThat(corpo(adm.get("/api/admin/pedidos/" + p.getId())).get("email").asString()).isEqualTo("maria@exemplo.com");
		assertThat(corpo(adm.get("/api/admin/pedidos/" + p.getId())).get("nome").asString()).as("nada mudou").isEqualTo("Maria Souza");

		// os outros campos (e o mesmo e-mail) seguem corrigiveis
		var outros = adm.patch("/api/admin/pedidos/" + p.getId(), "{\"nome\":\"Maria de Souza\",\"email\":\"maria@exemplo.com\"}");
		assertThat(outros.statusCode()).as(outros.body()).isEqualTo(200);
		assertThat(corpo(outros).get("nome").asString()).isEqualTo("Maria de Souza");
	}

	@Test
	void patchSemMudancaNaoAudita() throws Exception {
		var p = pedido(null, plano("Simples", 3000), null, "Maria Souza", "maria@exemplo.com", "52998224725", relogio.instant());
		var res = admin().patch("/api/admin/pedidos/" + p.getId(), "{\"nome\":\"Maria Souza\"}");
		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(jdbc.queryForObject("select count(*) from auditoria where entidade = 'pedido'", Integer.class)).isZero();
	}

	@Test
	void patchInvalidoE404() throws Exception {
		var p = pedido(null, plano("Simples", 3000), null, "Maria Souza", "maria@exemplo.com", "52998224725", relogio.instant());
		var adm = admin();
		for (var corpo : List.of("{\"sexo\":\"outro\"}", "{\"idade\":0}", "{\"email\":\"nao-e-email\"}", "{\"nome\":\"A\"}", "{\"nome\":\"   \"}", "{\"nome\":\" A \"}")) {
			var res = adm.patch("/api/admin/pedidos/" + p.getId(), corpo);
			assertThat(res.statusCode()).as(corpo).isEqualTo(400);
			assertThat(corpo(res).get("codigo").asString()).isEqualTo("dados_invalidos");
		}
		assertThat(adm.patch("/api/admin/pedidos/" + UUID.randomUUID(), "{\"nome\":\"Maria Silva\"}").statusCode()).isEqualTo(404);
	}

	static Set<String> chaves(JsonNode n) {
		return new java.util.LinkedHashSet<>(n.propertyNames());
	}
}
