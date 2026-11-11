package me.clama.pagamento.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import me.clama.conta.interno.Papel;
import me.clama.conta.web.Navegador;
import org.junit.jupiter.api.Test;

/** Quitacao em lote (D8, INV:160): so REGISTRADO do recorte, a contagem da previa confere, CANCELADO intacto, sem quitar duas vezes. */
class TransferenciaEmLoteTest extends PagamentoAdminBase {

	static final String ROTA = "/api/admin/repasses/transferencias";

	static String corpo(Long inst, String de, String ate, String comprovante, Object quantidade, long totalCentavos) {
		return "{\"instituicaoId\": %s, \"de\": \"%s\", \"ate\": \"%s\", \"comprovante\": \"%s\", \"quantidade\": %s, \"totalCentavos\": %d}".formatted(inst, de,
				ate, comprovante, quantidade, totalCentavos);
	}

	static String corpo(Long inst, String de, String ate, String comprovante, Object quantidade) {
		return corpo(inst, de, ate, comprovante, quantidade, 1800);
	}

	static String ciclo(Long inst, String comprovante, Object quantidade) {
		return corpo(inst, "2026-10-28", "2026-11-27", comprovante, quantidade);
	}

	/** 3 REGISTRADO no ciclo, mais um de cada coisa que o lote nao pode tocar. */
	record Cenario(Long lar, List<Long> alvo, long transferido, long cancelado, long antes, long depois, long outra) {}

	Cenario semear() {
		var lar = instituicao("Lar");
		var alvo = new ArrayList<Long>();
		alvo.add(repasseEm(pedido(3000, lar), lar, "REGISTRADO", "2026-10-28T03:00:00Z"));
		alvo.add(repasseEm(pedido(3000, lar), lar, "REGISTRADO", "2026-11-10T15:00:00Z"));
		alvo.add(repasseEm(pedido(3000, lar), lar, "REGISTRADO", "2026-11-28T02:59:59Z"));
		var transferido = repasseEm(pedido(3000, lar), lar, "TRANSFERIDO", "2026-11-10T15:00:00Z");
		var cancelado = repasseEm(pedido(3000, lar), lar, "CANCELADO", "2026-11-10T15:00:00Z");
		var antes = repasseEm(pedido(3000, lar), lar, "REGISTRADO", "2026-10-28T02:59:59Z");
		var depois = repasseEm(pedido(3000, lar), lar, "REGISTRADO", "2026-11-28T03:00:00Z");
		var outra = instituicao("Outra");
		var deOutra = repasseEm(pedido(3000, outra), outra, "REGISTRADO", "2026-11-10T15:00:00Z");
		return new Cenario(lar, alvo, transferido, cancelado, antes, depois, deOutra);
	}

	String status(long id) {
		return jdbc.queryForObject("select status from repasse where id = ?", String.class, id);
	}

	@Test
	void anonimoRecebe401EClienteRecebe403() throws Exception {
		var c = semear();
		var anonimo = new Navegador(porta);
		anonimo.get("/api/auth/eu");
		assertThat(anonimo.post(ROTA, ciclo(c.lar(), "PIX-1", 3)).statusCode()).isEqualTo(401);
		assertThat(entrar(Papel.CLIENTE).post(ROTA, ciclo(c.lar(), "PIX-1", 3)).statusCode()).isEqualTo(403);
		assertThat(contar("select count(*) from repasse where status = 'REGISTRADO'")).isEqualTo(6);
	}

	@Test
	void quitaSoOsRegistradosDoRecorteEAudita() throws Exception {
		var c = semear();
		var res = entrar(Papel.ADMIN).post(ROTA, ciclo(c.lar(), "PIX-E2026-11", 3));
		assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
		assertThat(json(res).get("quantidade").asInt()).isEqualTo(3);
		assertThat(json(res).get("totalCentavos").asInt()).isEqualTo(1800);
		for (var id : c.alvo()) {
			var l = jdbc.queryForMap("select status, comprovante, transferido_em from repasse where id = ?", id);
			assertThat(l.get("status")).isEqualTo("TRANSFERIDO");
			assertThat(l.get("comprovante")).isEqualTo("PIX-E2026-11");
			assertThat(l.get("transferido_em")).isNotNull();
		}
		// o que o lote nao pode tocar
		assertThat(status(c.transferido())).isEqualTo("TRANSFERIDO");
		assertThat(jdbc.queryForObject("select comprovante from repasse where id = ?", String.class, c.transferido())).isEqualTo("comp-teste");
		var cancelado = jdbc.queryForMap("select status, comprovante, transferido_em, cancelado_em from repasse where id = ?", c.cancelado());
		assertThat(cancelado.get("status")).isEqualTo("CANCELADO");
		assertThat(cancelado.get("comprovante")).isNull();
		assertThat(cancelado.get("transferido_em")).isNull();
		assertThat(cancelado.get("cancelado_em")).isNotNull();
		for (var id : List.of(c.antes(), c.depois(), c.outra())) assertThat(status(id)).isEqualTo("REGISTRADO");
		// auditoria: uma por repasse (como o individual) e uma do lote, sem o texto do comprovante
		assertThat(contar("select count(*) from auditoria where entidade = 'repasse' and acao = 'MUDANCA'")).isEqualTo(3);
		assertThat(contar("select count(*) from auditoria where entidade = 'repasse_lote' and entidade_id = ? and autor_id is not null",
				String.valueOf(c.lar()))).isEqualTo(1);
		var diff = jdbc.queryForObject("select diff::text from auditoria where entidade = 'repasse_lote'", String.class);
		assertThat(diff).contains("\"quantidade\": 3", "2026-10-28", "2026-11-27").doesNotContain("PIX-E2026-11");
		assertThat(jdbc.queryForObject("select string_agg(diff::text, '') from auditoria where entidade = 'repasse'", String.class)).doesNotContain("PIX-E2026-11");
	}

	@Test
	void quantidadeDiferenteDaPreviaE409ENadaMuda() throws Exception {
		var c = semear();
		var adm = entrar(Papel.ADMIN);
		for (var quantidade : List.of(2, 4)) {
			var res = adm.post(ROTA, ciclo(c.lar(), "PIX-1", quantidade));
			assertThat(res.statusCode()).as("quantidade " + quantidade).isEqualTo(409);
			assertThat(json(res).get("codigo").asString()).isEqualTo("recorte_mudou");
		}
		assertThat(contar("select count(*) from repasse where status = 'TRANSFERIDO'")).isEqualTo(1); // so o que ja era
		for (var id : c.alvo()) assertThat(status(id)).isEqualTo("REGISTRADO");
		assertThat(contar("select count(*) from auditoria where entidade in ('repasse', 'repasse_lote')")).isZero();
	}

	@Test
	void mesmaContagemComOutroConjuntoE409PeloTotalENadaMuda() throws Exception {
		var c = semear();
		// um estornado (sai) e um novo de valor diferente (entra): continua 3, mas o total nao e mais o da previa
		jdbc.update("update repasse set status = 'CANCELADO', cancelado_em = now() where id = ?", c.alvo().get(0));
		var novo = repasseEm(pedido(5000, c.lar()), c.lar(), "REGISTRADO", "2026-11-12T15:00:00Z");
		jdbc.update("update repasse set valor_centavos = 1000 where id = ?", novo);
		var res = entrar(Papel.ADMIN).post(ROTA, ciclo(c.lar(), "PIX-1", 3));
		assertThat(res.statusCode()).isEqualTo(409);
		assertThat(json(res).get("codigo").asString()).isEqualTo("recorte_mudou");
		assertThat(contar("select count(*) from repasse where status = 'TRANSFERIDO'")).isEqualTo(1);
		assertThat(contar("select count(*) from auditoria where entidade in ('repasse', 'repasse_lote')")).isZero();
	}

	@Test
	void repetirOLoteQuitadoE409PorqueNaoSobramPendentes() throws Exception {
		var c = semear();
		var adm = entrar(Papel.ADMIN);
		assertThat(adm.post(ROTA, ciclo(c.lar(), "PIX-1", 3)).statusCode()).isEqualTo(200);
		assertThat(adm.post(ROTA, ciclo(c.lar(), "PIX-2", 3)).statusCode()).isEqualTo(409);
		assertThat(contar("select count(*) from repasse where comprovante = 'PIX-2'")).isZero();
		assertThat(contar("select count(*) from auditoria where entidade = 'repasse_lote'")).isEqualTo(1);
	}

	@Test
	void comprovanteDeCentoEVinteCaracteresPassaVazioECentoEVinteEUmNao() throws Exception {
		var c = semear();
		var adm = entrar(Papel.ADMIN);
		for (var ruim : List.of("", "   ", "x".repeat(121))) {
			var res = adm.post(ROTA, ciclo(c.lar(), ruim, 3));
			assertThat(res.statusCode()).as("[" + ruim.length() + "]").isEqualTo(400);
		}
		assertThat(adm.post(ROTA, "{\"instituicaoId\": %d, \"de\": \"2026-10-28\", \"ate\": \"2026-11-27\", \"quantidade\": 3, \"totalCentavos\": 1800}".formatted(c.lar())).statusCode())
				.isEqualTo(400); // sem comprovante
		assertThat(contar("select count(*) from repasse where status = 'TRANSFERIDO'")).isEqualTo(1);
		assertThat(contar("select count(*) from auditoria where entidade in ('repasse', 'repasse_lote')")).isZero();
		assertThat(adm.post(ROTA, ciclo(c.lar(), "x".repeat(120), 3)).statusCode()).isEqualTo(200);
		assertThat(jdbc.queryForObject("select length(comprovante) from repasse where id = ?", Integer.class, c.alvo().get(0))).isEqualTo(120);
	}

	@Test
	void comprovanteDeUmCaractereBasta() throws Exception {
		var c = semear();
		assertThat(entrar(Papel.ADMIN).post(ROTA, ciclo(c.lar(), "x", 3)).statusCode()).isEqualTo(200);
	}

	@Test
	void corpoInvalidoE400() throws Exception {
		var c = semear();
		var adm = entrar(Papel.ADMIN);
		var ruins = List.of(
				ciclo(c.lar(), "PIX", 0), ciclo(c.lar(), "PIX", -1), ciclo(c.lar(), "PIX", "null"),
				corpo(null, "2026-10-28", "2026-11-27", "PIX", 3),
				corpo(c.lar(), "2026-11-28", "2026-11-27", "PIX", 3), // de depois de ate
				corpo(c.lar(), "28/10/2026", "2026-11-27", "PIX", 3),
				corpo(c.lar(), "", "2026-11-27", "PIX", 3), corpo(c.lar(), "2026-10-28", "", "PIX", 3));
		for (var corpo : ruins) assertThat(adm.post(ROTA, corpo).statusCode()).as(corpo).isEqualTo(400);
		assertThat(adm.post(ROTA, "{}").statusCode()).isEqualTo(400);
		assertThat(contar("select count(*) from repasse where status = 'TRANSFERIDO'")).isEqualTo(1);
	}

	@Test
	void instituicaoInexistenteE404() throws Exception {
		semear();
		assertThat(entrar(Papel.ADMIN).post(ROTA, ciclo(999999L, "PIX", 1)).statusCode()).isEqualTo(404);
	}

	@Test
	void doisLotesSimultaneosNaoQuitamDuasVezes() throws Exception {
		var lar = instituicao("Lar");
		for (int i = 0; i < 25; i++) repasseEm(pedido(3000, lar), lar, "REGISTRADO", "2026-11-10T15:00:00Z");
		var a = entrar(Papel.ADMIN);
		var b = entrar(Papel.ADMIN);
		var largada = new CountDownLatch(1);
		var pool = Executors.newFixedThreadPool(2);
		try {
			var fa = pool.submit(() -> { largada.await(); return a.post(ROTA, corpo(lar, "2026-10-28", "2026-11-27", "PIX-A", 25, 15000)).statusCode(); });
			var fb = pool.submit(() -> { largada.await(); return b.post(ROTA, corpo(lar, "2026-10-28", "2026-11-27", "PIX-B", 25, 15000)).statusCode(); });
			largada.countDown();
			var codigos = new ArrayList<>(List.of(fa.get(), fb.get()));
			java.util.Collections.sort(codigos);
			assertThat(codigos).containsExactly(200, 409);
			var vencedor = fa.get() == 200 ? "PIX-A" : "PIX-B";
			assertThat(contar("select count(*) from repasse where status = 'TRANSFERIDO' and comprovante = ?", vencedor)).isEqualTo(25);
			assertThat(contar("select count(*) from repasse where comprovante is not null and comprovante <> ?", vencedor)).isZero();
			assertThat(contar("select count(*) from auditoria where entidade = 'repasse'")).isEqualTo(25); // uma por repasse, uma vez so
			assertThat(contar("select count(*) from auditoria where entidade = 'repasse_lote'")).isEqualTo(1);
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void estornoConcorrenteQueCancelaFazOLoteVerContagemDiferente() throws Exception {
		var c = semear();
		jdbc.update("update repasse set status = 'CANCELADO', cancelado_em = now() where id = ?", c.alvo().get(0)); // o estorno chegou depois da previa
		var res = entrar(Papel.ADMIN).post(ROTA, ciclo(c.lar(), "PIX", 3));
		assertThat(res.statusCode()).isEqualTo(409);
		assertThat(status(c.alvo().get(0))).isEqualTo("CANCELADO");
		assertThat(status(c.alvo().get(1))).isEqualTo("REGISTRADO");
	}
}
