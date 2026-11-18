package me.clama.pagamento.web;

import static org.assertj.core.api.Assertions.assertThat;

import me.clama.conta.web.Navegador;
import org.junit.jupiter.api.Test;

/** GET /api/metas: arrecadado do ciclo em curso (28 a 27, dias de Sao Paulo), so repasses validos. */
class MetasTest extends PagamentoAdminBase {

	@Test
	void metasSomamSoRepassesValidosDoCicloEmCurso() throws Exception {
		relogioEm("2026-11-17T15:00:00Z"); // ciclo 28/10 a 27/11
		var lar = instituicao("Lar");
		repasseEm(pedido(3000, lar), lar, "REGISTRADO", "2026-11-05T12:00:00Z");
		repasseEm(pedido(2000, lar), lar, "TRANSFERIDO", "2026-10-28T03:00:00Z"); // 00:00 de 28/10 em SP: dentro
		repasseEm(pedido(3000, lar), lar, "CANCELADO", "2026-11-06T12:00:00Z");
		repasseEm(pedido(3000, lar), lar, "REGISTRADO", "2026-10-28T02:59:59Z"); // 27/10 em SP: ciclo anterior
		repasseEm(pedido(3000, lar), lar, "REGISTRADO", "2026-11-28T03:00:00Z"); // 28/11 em SP: proximo ciclo
		jdbc.update("update repasse set valor_centavos = 400 where status = 'TRANSFERIDO'");
		jdbc.update("update repasse set valor_centavos = 999 where status = 'CANCELADO'");
		var res = new Navegador(porta).get("/api/metas");
		assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
		var j = json(res);
		assertThat(j.get("percentual").asInt()).isEqualTo(20);
		assertThat(j.get("de").asString()).isEqualTo("2026-10-28");
		assertThat(j.get("ate").asString()).isEqualTo("2026-11-27");
		assertThat(j.get("instituicoes").get(0).get("instituicaoId").asLong()).isEqualTo(lar);
		assertThat(j.get("instituicoes").get(0).get("arrecadadoCentavos").asLong()).isEqualTo(1000);
		assertThat(res.headers().firstValue("Cache-Control").orElse("")).contains("max-age=300");
	}

	@Test
	void instituicaoSemRepasseApareceComZero() throws Exception {
		relogioEm("2026-11-17T15:00:00Z");
		var lar = instituicao("Lar");
		var inativa = instituicao("Fechada");
		jdbc.update("update instituicao set ativo = false where id = ?", inativa);
		var j = json(new Navegador(porta).get("/api/metas"));
		assertThat(j.get("instituicoes").size()).isEqualTo(1);
		assertThat(j.get("instituicoes").get(0).get("instituicaoId").asLong()).isEqualTo(lar);
		assertThat(j.get("instituicoes").get(0).get("arrecadadoCentavos").asLong()).isZero();
	}
}
