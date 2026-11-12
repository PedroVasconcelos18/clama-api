package me.clama.lgpd;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import me.clama.conta.interno.Papel;
import me.clama.conta.web.Navegador;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * D16, de ponta a ponta pela API: a mesma pessoa doou sem conta (Gmail com ponto e "+tag") e logada (Gmail simples). Busca,
 * exportacao e exclusao: o pedido pago e anonimizado e continua no relatorio de repasse como "(sem nome)"; o pedido sem
 * pagamento some; a conta e excluida; o motivo do ban e redigido; o bloqueio do freemium fica.
 */
class LgpdFluxoTest extends LgpdBase {

	@Test
	void buscaExportaEExcluiOTitularSemPerderODinheiro() throws Exception {
		var sufixo = unico();
		var gmail = "ana" + sufixo + "@gmail.com";
		var variante = "A.n.a" + sufixo + "+oracao@gmail.com";
		var conta = conta(gmail, Papel.CLIENTE);
		var adminConta = conta("adm-" + unico() + "@exemplo.com", Papel.ADMIN);
		var lar = instituicao("Lar Esperança");
		var plano = plano();
		var logadoPago = pedido(gmail, conta.getId(), plano, lar);
		enviado(logadoPago);
		paga(logadoPago, 9001);
		repasse(logadoPago, lar);
		var anonimoPago = pedido(variante, null, plano, lar); // doacao anonima, mesmo Gmail em outra grafia
		enviado(anonimoPago);
		paga(anonimoPago, 9002);
		repasse(anonimoPago, lar);
		var anonimoSemPagamento = pedido(variante, null, plano, lar);
		cobranca(anonimoSemPagamento, 9003);
		var deOutraPessoa = pedido("outra-" + sufixo + "@exemplo.com", null, plano, lar);
		blog(conta.getId(), 31);
		banimentos.banir(adminConta.getId(), conta.getId(), "ofendeu outra pessoa " + RASTRO);
		jdbc.update("insert into freemium_bloqueio (email_hmac, cpf_hmac, criado_em) values (?, ?, now())", hmac.email(gmail), hmac.cpf("52998224725"));
		var titular = entrar(gmail);
		var admin = entrar(adminConta.getEmail());
		var super_ = logado(Papel.SUPERUSUARIO);
		var hoje = LocalDate.now(ZoneId.of("America/Sao_Paulo"));
		var relatorio = "/api/admin/repasses/relatorio?instituicaoId=" + lar + "&de=" + hoje.minusDays(1) + "&ate=" + hoje.plusDays(1);

		// o relatorio do repasse mostra o nome abreviado (primeiro e ultimo) das duas doacoes
		var antes = json(admin.post("/api/admin/lgpd/busca", busca("a.n.a" + sufixo + "@googlemail.com")));
		assertThat(antes.get("pedidoIds")).hasSize(3);
		assertThat(antes.at("/pedidos/aAnonimizar").asInt()).isEqualTo(2);
		assertThat(antes.at("/pedidos/aApagar").asInt()).isEqualTo(1);
		assertThat(doadores(admin, relatorio)).containsExactly("Maria Z.", "Maria Z.");

		// exportacao pelo ADMIN: tudo do titular, nada do alheio
		var exp = admin.post("/api/admin/lgpd/exportacao", busca(gmail));
		assertThat(exp.statusCode()).isEqualTo(200);
		var j = json(exp);
		assertThat(j.get("pedidos")).extracting(n -> n.get("id").asString())
				.containsExactlyInAnyOrder(logadoPago.toString(), anonimoPago.toString(), anonimoSemPagamento.toString());
		assertThat(exp.body()).doesNotContain(deOutraPessoa.toString());
		assertThat(j.at("/blog/comentarios/0/ip").asString()).isEqualTo(IP_COMENTARIO);

		// exclusao pelo SUPERUSUARIO
		var res = super_.post("/api/admin/lgpd/exclusao", "{\"email\":\"" + gmail + "\",\"confirmacao\":\"" + gmail + "\",\"pedidoIds\":"
				+ antes.get("pedidoIds") + ",\"contaIds\":" + antes.get("contaIds") + "}");
		assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
		assertThat(json(res).get("pedidosApagados").asInt()).isEqualTo(1);
		assertThat(json(res).get("pedidosAnonimizados").asInt()).isEqualTo(2);
		assertThat(json(res).get("contasExcluidas").asInt()).isEqualTo(1);

		// o dinheiro continua: os dois pedidos pagos seguem no relatorio, sem nome
		assertThat(doadores(admin, relatorio)).containsExactly("(sem nome)", "(sem nome)");
		assertThat(contar("select count(*) from repasse")).isEqualTo(2);
		assertThat(contar("select count(*) from cobranca where paga_em is not null")).isEqualTo(2);
		assertThat(pedidos.findById(anonimoSemPagamento)).isEmpty();
		assertThat(pedidos.findById(deOutraPessoa)).isPresent();
		assertThat(usuarios.findById(conta.getId())).isEmpty();
		assertThat(titular.get("/api/auth/eu").statusCode()).isEqualTo(401);
		assertThat(jdbc.queryForObject("select diff->>'motivo' from auditoria where entidade = 'banimento' and entidade_id = ?", String.class,
				String.valueOf(conta.getId()))).isEqualTo("[removido na exclusão LGPD]");
		assertThat(contar("select count(*) from freemium_bloqueio")).isEqualTo(1);

		// nada mais do titular: a busca e a exportacao nao acham, e o e-mail pode ser usado de novo
		assertThat(json(admin.post("/api/admin/lgpd/busca", busca(variante))).get("pedidoIds")).isEmpty();
		assertThat(admin.post("/api/admin/lgpd/exportacao", busca(gmail)).statusCode()).isEqualTo(404);
	}

	/** O nome de cada linha da previa do relatorio, na ordem do ledger. */
	java.util.List<String> doadores(Navegador nav, String rota) throws Exception {
		var res = nav.get(rota);
		assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
		var nomes = new java.util.ArrayList<String>();
		for (JsonNode l : json(res).get("linhas")) nomes.add(l.get("doador").asString());
		return nomes;
	}
}
