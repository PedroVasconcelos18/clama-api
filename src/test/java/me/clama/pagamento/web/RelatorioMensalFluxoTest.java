package me.clama.pagamento.web;

import static org.assertj.core.api.Assertions.assertThat;

import me.clama.conta.interno.Papel;
import me.clama.infra.alerta.AlertaAoAdmin;
import me.clama.pagamento.interno.relatorio.RelatorioMensal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * D16: o dia 28 as 10:00 de Sao Paulo de ponta a ponta, sem mocks. Tres instituicoes com doacoes (uma inativa, com repasse no
 * ciclo) e uma ativa sem nenhuma: quatro linhas em {@code relatorio_repasse}, o e-mail com as quatro e sem dado de doador, a rota
 * dos resumos e o CSV da instituicao. Bordas do ciclo: 27/11 23:59:59 SP dentro, 28/11 00:00 SP fora (e 28/10 00:00 dentro).
 */
class RelatorioMensalFluxoTest extends PagamentoAdminBase {

	@Autowired RelatorioMensal tarefa;
	@Autowired AlertaAoAdmin.Props props;

	void doacao(String nome, Long inst, String criadoEmUtc, long mp) {
		var p = pedido(nome, 3000, inst);
		paga(p, mp);
		repasseEm(p, inst, "REGISTRADO", criadoEmUtc);
	}

	@Test
	void dia28As10ProduzQuatroResumosUmEmailSemPiiELinksQueFuncionam() throws Exception {
		var lar = instituicao("Lar Esperança");
		var casa = instituicao("Casa Pela Vida");
		var antiga = instituicao("Associação Antiga");
		var vazia = instituicao("Mar de Espanha");
		jdbc.update("update instituicao set ativo = false where id = ?", antiga);
		doacao("Maria Silva Santos", lar, "2026-10-28T03:00:00Z", 1); // 28/10 00:00 SP: primeiro instante do ciclo
		doacao("Joao Pereira Souza", lar, "2026-11-28T02:59:59Z", 2); // 27/11 23:59:59 SP: ultimo instante
		doacao("Ana Costa Lima", lar, "2026-11-28T03:00:00Z", 3); // 28/11 00:00 SP: proximo ciclo
		doacao("Carlos Dias Melo", lar, "2026-10-28T02:59:59Z", 4); // 27/10 23:59:59 SP: ciclo anterior
		doacao("Beatriz Nunes Rocha", casa, "2026-11-10T15:00:00Z", 5);
		doacao("Paulo Ramos Vieira", antiga, "2026-11-15T15:00:00Z", 6);
		relogioEm("2026-11-28T13:00:00Z"); // 10:00 SP

		tarefa.executeRecurringly(null, null); // o caminho do agendador

		assertThat(contar("select count(*) from relatorio_repasse")).isEqualTo(4);
		assertThat(jdbc.queryForObject("select quantidade from relatorio_repasse where instituicao_id = ?", Integer.class, lar)).isEqualTo(2);
		assertThat(jdbc.queryForObject("select quantidade from relatorio_repasse where instituicao_id = ?", Integer.class, vazia)).isZero();
		var enviados = caixa.para(props.emailAdmin());
		assertThat(enviados).hasSize(1);
		var e = enviados.get(0).email();
		var tudo = e.assunto() + e.texto() + e.html();
		assertThat(e.texto()).contains("Lar Esperança: 2 doações, R$ 12,00 a repassar", "Casa Pela Vida: 1 doação, R$ 6,00 a repassar",
				"Associação Antiga: 1 doação, R$ 6,00 a repassar", "Mar de Espanha: 0 doações, R$ 0,00 a repassar", "Faltou: Nenhuma.");
		assertThat(tudo).doesNotContain("Maria", "Joao", "Ana Costa", "Carlos", "Beatriz", "Paulo", "exemplo.com");
		assertThat(enviados.get(0).chave()).matches("relatorio-repasse/2026-11-27/[0-9a-f]{12}");
		assertThat(contar("select count(*) from tarefa_execucao where tarefa = 'relatorio-repasse-mensal' and ok and not manual")).isEqualTo(1);

		relogio.zerar();
		var adm = entrar(Papel.ADMIN);
		var mensais = json(adm.get("/api/admin/repasses/relatorios-mensais"));
		assertThat(mensais.get("page").get("totalElements").asInt()).isEqualTo(4);
		var csv = adm.get("/api/admin/repasses/relatorio.csv?instituicaoId=" + lar + "&de=2026-10-28&ate=2026-11-27");
		assertThat(csv.statusCode()).isEqualTo(200);
		assertThat(csv.body()).contains("Maria S.;", "Joao S.;", "TOTAL (2 doações)").doesNotContain("Ana C.", "Carlos").doesNotContain("@");
	}
}
