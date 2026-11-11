package me.clama.pedido.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import me.clama.conta.interno.Papel;
import me.clama.conta.web.Navegador;
import me.clama.pedido.interno.Pedido;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Metricas de pedido (D14): dias em SP, o que conta como pago, faturamento, latencia p50/p90 e filtros invalidos. */
class MetricasDePedidoTest extends PedidoWebTeste {

	static final String DIA = "?de=2026-03-10&ate=2026-03-10";
	// 2026-03-10 em SP (UTC-3): [03-10T03:00Z, 03-11T03:00Z)
	static final Instant MEIO_DIA = Instant.parse("2026-03-10T15:00:00Z");

	Navegador admin() throws Exception {
		var email = "ped-met-" + UUID.randomUUID() + "@exemplo.com";
		Navegador.usuario(usuarios, encoder, email, "senha-forte-123", Papel.ADMIN);
		var nav = new Navegador(porta);
		assertThat(nav.login(email, "senha-forte-123").statusCode()).isEqualTo(200);
		return nav;
	}

	JsonNode metricas(String query) throws Exception {
		var res = admin().get("/api/admin/metricas/pedidos" + query);
		assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
		return corpo(res);
	}

	Pedido.Dados dados(Long plano, long valor, Instant em) {
		return new Pedido.Dados(null, plano, null, valor, "Maria Souza", "maria@exemplo.com", "52998224725", "11999990000",
				(short) 40, "feminino", "Pela família", em, null, "1");
	}

	Pedido pago(Long plano, long valor, Instant pagoEm) {
		var p = Pedido.novo(dados(plano, valor, pagoEm), hmac, pagoEm.minusSeconds(600));
		p.confirmarPagamento(pagoEm);
		return salvar(p);
	}

	Pedido enviado(Long plano, Instant pagoEm, long segundos) {
		var p = Pedido.novo(dados(plano, 3000, pagoEm), hmac, pagoEm.minusSeconds(600));
		p.confirmarPagamento(pagoEm);
		p.iniciarGeracao(pagoEm);
		p.registrarOracao("Senhor...", pagoEm);
		p.marcarEnviado(pagoEm.plusSeconds(segundos));
		return salvar(p);
	}

	@Test
	void anonimoRecebe401EClienteRecebe403() throws Exception {
		assertThat(anonimo().get("/api/admin/metricas/pedidos").statusCode()).isEqualTo(401);
		assertThat(logado().nav().get("/api/admin/metricas/pedidos").statusCode()).isEqualTo(403);
	}

	@Test
	void diaDeSaoPauloCortaNaMeiaNoiteDeSp() throws Exception {
		var plano = plano("Simples", 3000);
		pago(plano, 3000, Instant.parse("2026-03-10T02:59:59Z")); // 09/03 23:59:59 em SP: fora
		pago(plano, 3000, Instant.parse("2026-03-10T03:00:00Z")); // 10/03 00:00:00: dentro
		pago(plano, 3000, Instant.parse("2026-03-11T02:59:59Z")); // 10/03 23:59:59: dentro
		pago(plano, 3000, Instant.parse("2026-03-11T03:00:00Z")); // 11/03 00:00:00: fora

		var m = metricas(DIA);

		assertThat(m.get("pagos").asLong()).isEqualTo(2);
		assertThat(m.get("faturamentoCentavos").asLong()).isEqualTo(6000);
		assertThat(m.get("ticketMedioCentavos").asLong()).isEqualTo(3000);
	}

	@Test
	void gratuitoEFreemiumPendenteNaoEntramNoFaturamento() throws Exception {
		var plano = plano("Simples", 3000);
		pago(plano, 3000, MEIO_DIA);
		salvar(Pedido.gratuito(dados(planoTecnico("GRATUITO"), 0, MEIO_DIA), hmac, MEIO_DIA));
		var confirmado = Pedido.freemium(dados(planoTecnico("GRATUITO"), 0, MEIO_DIA), hmac, MEIO_DIA);
		confirmado.confirmarEmail(MEIO_DIA);
		salvar(confirmado);
		salvar(Pedido.freemium(dados(planoTecnico("GRATUITO"), 0, MEIO_DIA), hmac, MEIO_DIA)); // link ainda nao usado
		salvar(Pedido.novo(dados(plano, 3000, MEIO_DIA), hmac, MEIO_DIA)); // aguardando pagamento

		var m = metricas(DIA);

		assertThat(m.get("pagos").asLong()).isEqualTo(1);
		assertThat(m.get("gratuitos").asLong()).isEqualTo(2);
		assertThat(m.get("criados").asLong()).isEqualTo(5);
		assertThat(m.get("faturamentoCentavos").asLong()).isEqualTo(3000);
		assertThat(m.get("porPlano")).hasSize(1);
	}

	@Test
	void ticketMedioEPorPlano() throws Exception {
		var simples = plano("Simples", 3000);
		var longo = plano("Longa", 5000);
		pago(simples, 3000, MEIO_DIA);
		pago(simples, 3000, MEIO_DIA.plusSeconds(60));
		pago(longo, 5000, MEIO_DIA.plusSeconds(120));

		var m = metricas(DIA);

		assertThat(m.get("faturamentoCentavos").asLong()).isEqualTo(11000);
		assertThat(m.get("ticketMedioCentavos").asLong()).isEqualTo(3667); // 3666,67
		var porPlano = m.get("porPlano");
		assertThat(porPlano).hasSize(2);
		for (var l : porPlano) {
			if (l.get("plano").asString().equals("Simples")) {
				assertThat(l.get("quantidade").asLong()).isEqualTo(2);
				assertThat(l.get("faturamentoCentavos").asLong()).isEqualTo(6000);
			} else {
				assertThat(l.get("plano").asString()).isEqualTo("Longa");
				assertThat(l.get("quantidade").asLong()).isEqualTo(1);
				assertThat(l.get("faturamentoCentavos").asLong()).isEqualTo(5000);
			}
		}
	}

	@Test
	void latenciaP50EP90DosEnviadosNoPeriodo() throws Exception {
		var plano = plano("Simples", 3000);
		enviado(plano, MEIO_DIA, 10);
		enviado(plano, MEIO_DIA, 20);
		enviado(plano, MEIO_DIA, 30);
		enviado(plano, Instant.parse("2026-03-01T15:00:00Z"), 9999); // enviado em outro dia: fora da latencia

		var m = metricas(DIA);

		assertThat(m.get("enviados").asLong()).isEqualTo(3);
		assertThat(m.at("/latencia/p50Segundos").asDouble()).isEqualTo(20.0);
		assertThat(m.at("/latencia/p90Segundos").asDouble()).isEqualTo(28.0); // 20 + 0,8 * (30 - 20)
	}

	@Test
	void semEnviadosALatenciaEhNula() throws Exception {
		var m = metricas(DIA);

		assertThat(m.get("pagos").asLong()).isZero();
		assertThat(m.get("ticketMedioCentavos").asLong()).isZero();
		assertThat(m.get("latencia").get("p50Segundos").isNull()).isTrue();
		assertThat(m.get("latencia").get("p90Segundos").isNull()).isTrue();
		assertThat(m.get("porPlano")).isEmpty();
	}

	@Test
	void reenvioNaoMudaEnviadaEm() throws Exception {
		var p = enviado(plano("Simples", 3000), MEIO_DIA, 10);
		var antes = jdbc.queryForObject("select enviada_em from pedido where id = ?", java.time.OffsetDateTime.class, p.getId());

		var recarregado = pedidos.findById(p.getId()).orElseThrow();
		recarregado.marcarEnviado(MEIO_DIA.plusSeconds(86400 * 3)); // reenvio tres dias depois
		pedidos.saveAndFlush(recarregado);

		var depois = jdbc.queryForObject("select enviada_em from pedido where id = ?", java.time.OffsetDateTime.class, p.getId());
		assertThat(depois).isEqualTo(antes);
		assertThat(metricas(DIA).get("enviados").asLong()).isEqualTo(1);
		assertThat(metricas("?de=2026-03-13&ate=2026-03-13").get("enviados").asLong()).isZero();
	}

	@Test
	void emErroAgoraIndependeDoPeriodo() throws Exception {
		var p = Pedido.novo(dados(plano("Simples", 3000), 3000, MEIO_DIA), hmac, MEIO_DIA);
		p.confirmarPagamento(MEIO_DIA);
		p.falhar("falha_geracao", MEIO_DIA);
		salvar(p);

		assertThat(metricas("?de=2020-01-01&ate=2020-01-02").get("emErroAgora").asLong()).isEqualTo(1);
	}

	@Test
	void padraoEOMesCorrenteAteHojeEmSp() throws Exception {
		var plano = plano("Simples", 3000);
		var sp = ZoneId.of("America/Sao_Paulo");
		var inicioDoMes = LocalDate.now(relogio.withZone(sp)).withDayOfMonth(1).atStartOfDay(sp).toInstant();
		pago(plano, 3000, inicioDoMes.minusSeconds(1)); // mes passado: fora
		pago(plano, 3000, inicioDoMes); // dia 1 00:00 em SP: dentro
		pago(plano, 3000, relogio.instant().minusSeconds(1)); // agora: dentro

		assertThat(metricas("").get("pagos").asLong()).isEqualTo(2);
	}

	@Test
	void filtroInvalidoEh400() throws Exception {
		var nav = admin();
		for (var q : new String[] { "?de=ontem", "?ate=2026-13-45", "?de=2026-03-11&ate=2026-03-10", "?period=week&de=10/03/2026" }) {
			var res = nav.get("/api/admin/metricas/pedidos" + q);
			assertThat(res.statusCode()).as(q).isEqualTo(400);
			assertThat(corpo(res).get("codigo").asString()).isEqualTo("dados_invalidos");
		}
	}

	@Test
	void formaDaRespostaSemCanalNemListaDeErros() throws Exception {
		var m = metricas(DIA);

		assertThat(m.propertyNames()).containsExactlyInAnyOrder("criados", "pagos", "gratuitos", "enviados", "emErroAgora",
				"faturamentoCentavos", "ticketMedioCentavos", "porPlano", "latencia");
	}
}
