package me.clama.pagamento.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import me.clama.conta.interno.Papel;
import org.junit.jupiter.api.Test;

/** CSV de auditoria interna (D4, INV:156): nome inteiro, e-mail e id do MP da cobranca paga; cada download grava auditoria. */
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class RelatorioInternoTest extends PagamentoAdminBase {

	static final String CICLO = "de=2026-10-28&ate=2026-11-27";
	static final String ROTA = "/api/admin/repasses/relatorio-interno.csv?instituicaoId=";

	@Test
	void colunasAvisoNomeCompletoEmailIdDoMpEIdSemGateway() throws Exception {
		var lar = instituicao("Lar; Esperança");
		var pago = pedido("Maria Silva Santos", 3000, lar);
		paga(pago, 1325814423L);
		repasseEm(pago, lar, "TRANSFERIDO", "2026-11-01T12:00:00Z");
		var semGateway = pedido("João Souza", 3000, lar);
		repasseEm(semGateway, lar, "REGISTRADO", "2026-11-02T12:00:00Z");
		var res = entrar(Papel.ADMIN).get(ROTA + lar + "&" + CICLO);
		assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
		assertThat(res.headers().firstValue("Content-Disposition").orElseThrow())
				.contains("filename=\"auditoria-INTERNA-repasse-lar-esperanca-2026-10-28_a_2026-11-27.csv\"");
		var linhas = List.of(res.body().split("\r\n", -1));
		assertThat(linhas.get(0)).isEqualTo("﻿\"AUDITORIA INTERNA de repasse — Lar; Esperança\"");
		assertThat(linhas.get(1)).isEqualTo("Período: 28/10/2026 a 27/11/2026");
		assertThat(linhas.get(2)).isEqualTo("USO INTERNO — contém dado pessoal. Não entregar à instituição.");
		assertThat(linhas.get(4)).isEqualTo("Doador (nome completo);E-mail;ID do pedido;ID do pagamento no gateway;Status do pedido;Data do pedido;"
				+ "Data da confirmação;Valor no ledger (R$);Valor atual do pedido (R$);Percentual;Repasse (R$);Status do repasse;"
				+ "Transferido em;Comprovante;Divergência");
		assertThat(linhas.get(5)).startsWith("Maria Silva Santos;maria@exemplo.com;" + pago + ";1325814423;AGUARDANDO_PAGAMENTO;")
				.contains(";01/11/2026;30,00;30,00;20%;6,00;TRANSFERIDO;").endsWith(";comp-teste;");
		assertThat(linhas.get(6)).startsWith("João Souza;maria@exemplo.com;" + semGateway + ";(sem id no gateway);AGUARDANDO_PAGAMENTO;")
				.contains(";02/11/2026;30,00;30,00;20%;6,00;REGISTRADO;;;");
		assertThat(linhas.get(8)).isEqualTo("TOTAL (2 doações);;;;;;;60,00;;;12,00;;;;");
		assertThat(res.body()).doesNotContain("52998224725", "11987654321", "Pela família");
	}

	@Test
	void divergenciaEntreOLedgerEOValorAtualDoPedido() throws Exception {
		var lar = instituicao("Lar");
		var p = pedido("Ana Lima", 3000, lar);
		repasseEm(p, lar, "REGISTRADO", "2026-11-02T12:00:00Z");
		jdbc.update("update pedido set valor_centavos = 0 where id = ?", p);
		var res = entrar(Papel.ADMIN).get(ROTA + lar + "&" + CICLO);
		assertThat(res.body()).contains(";30,00;0,00;20%;6,00;REGISTRADO;;;pedido hoje vale 0 centavos, ledger registrou 3000\r\n");
	}

	@Test
	void foraOCanceladoEOPeriodoVazioTambemSaiZerado() throws Exception {
		var lar = instituicao("Lar");
		repasseEm(pedido("Ana Lima", 3000, lar), lar, "CANCELADO", "2026-11-02T12:00:00Z");
		var res = entrar(Papel.ADMIN).get(ROTA + lar + "&" + CICLO);
		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(res.body()).doesNotContain("Ana Lima").endsWith("TOTAL (0 doações);;;;;;;0,00;;;0,00;;;;\r\n");
		assertThat(contar("select count(*) from auditoria where entidade = 'relatorio_interno'")).isEqualTo(1);
	}

	@Test
	void cadaDownloadGravaAuditoriaSemNomeNemEmail() throws Exception {
		var lar = instituicao("Lar");
		repasseEm(pedido("Maria Silva Santos", 3000, lar), lar, "REGISTRADO", "2026-11-02T12:00:00Z");
		var adm = entrar(Papel.ADMIN);
		assertThat(adm.get(ROTA + lar + "&" + CICLO).statusCode()).isEqualTo(200);
		assertThat(adm.get(ROTA + lar + "&" + CICLO).statusCode()).isEqualTo(200);
		assertThat(contar("select count(*) from auditoria where entidade = 'relatorio_interno' and entidade_id = ? and acao = 'ADICAO' and autor_id is not null",
				String.valueOf(lar))).isEqualTo(2);
		var diff = jdbc.queryForObject("select diff::text from auditoria where entidade = 'relatorio_interno' limit 1", String.class);
		assertThat(diff).contains("\"instituicaoId\"", "2026-10-28", "2026-11-27").doesNotContain("Maria", "@");
		// o CSV da instituicao e a previa nao exportam PII e nao auditam
		adm.get("/api/admin/repasses/relatorio.csv?instituicaoId=" + lar + "&" + CICLO);
		adm.get("/api/admin/repasses/relatorio?instituicaoId=" + lar + "&" + CICLO);
		assertThat(contar("select count(*) from auditoria where entidade = 'relatorio_interno'")).isEqualTo(2);
	}

	@Test
	void nomeEEmailNaoVaoParaOLog(org.springframework.boot.test.system.CapturedOutput log) throws Exception {
		var lar = instituicao("Lar");
		repasseEm(pedido("Zuleica Quintanilha", 3000, lar), lar, "REGISTRADO", "2026-11-02T12:00:00Z");
		assertThat(entrar(Papel.ADMIN).get(ROTA + lar + "&" + CICLO).statusCode()).isEqualTo(200);
		assertThat(log.getAll()).doesNotContain("Zuleica", "Quintanilha", "maria@exemplo.com");
	}
}
