package me.clama.oracao.interno;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import me.clama.BancoDeTeste;
import me.clama.conta.interno.Papel;
import me.clama.conta.interno.UsuarioRepository;
import me.clama.conta.web.Navegador;
import me.clama.infra.web.FiltrosDeAdmin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

/** D14: soma de tokens, custo estimado (precos do application.yml) e custo nulo sem preco. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class MetricasDaOracaoTest {

	static final String DIA = "?de=2026-03-10&ate=2026-03-10";

	@LocalServerPort int porta;
	@Autowired JdbcTemplate jdbc;
	@Autowired JdbcClient jdbcClient;
	@Autowired UsuarioRepository usuarios;
	@Autowired PasswordEncoder encoder;
	@Autowired OracaoProps props;
	@Autowired MetricasDaOracao metricas;

	@BeforeEach
	@AfterEach
	void limpar() {
		jdbc.update("delete from oracao_geracao");
		jdbc.update("delete from usuario where email like 'orc-met-%'");
	}

	void geracao(String em, int entrada, int saida, int escrita, int leitura, int ms) {
		geracao("claude-sonnet-4-6", em, entrada, saida, escrita, leitura, ms);
	}

	void geracao(String modelo, String em, int entrada, int saida, int escrita, int leitura, int ms) {
		jdbc.update("""
				insert into oracao_geracao (pedido_id, criado_em, modelo, tokens_entrada, tokens_saida, tokens_cache_escrita,
				  tokens_cache_leitura, duracao_ms) values (?, ?::timestamptz, ?, ?, ?, ?, ?, ?)""",
				UUID.randomUUID(), em, modelo, entrada, saida, escrita, leitura, ms);
	}

	Navegador nav(Papel papel) throws Exception {
		var email = "orc-met-" + UUID.randomUUID() + "@exemplo.com";
		Navegador.usuario(usuarios, encoder, email, "senha-forte-123", papel);
		var nav = new Navegador(porta);
		assertThat(nav.login(email, "senha-forte-123").statusCode()).isEqualTo(200);
		return nav;
	}

	JsonNode resumo(String query) throws Exception {
		var res = nav(Papel.ADMIN).get("/api/admin/metricas/oracao" + query);
		assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
		return Navegador.json(res);
	}

	@Test
	void anonimoRecebe401EClienteRecebe403() throws Exception {
		assertThat(new Navegador(porta).get("/api/admin/metricas/oracao").statusCode()).isEqualTo(401);
		assertThat(nav(Papel.CLIENTE).get("/api/admin/metricas/oracao").statusCode()).isEqualTo(403);
	}

	@Test
	void somaOsTokensEEstimaOCustoPeloPreco() throws Exception {
		geracao("2026-03-10T15:00:00Z", 600, 100, 2000, 0, 1000);
		geracao("2026-03-10T16:00:00Z", 400, 400, 0, 10000, 3000);
		geracao("2026-03-11T03:00:00Z", 9999, 9999, 9999, 9999, 9); // 11/03 em SP: fora

		var r = resumo(DIA);

		assertThat(r.get("geracoes").asLong()).isEqualTo(2);
		assertThat(r.get("tokensEntrada").asLong()).isEqualTo(1000);
		assertThat(r.get("tokensSaida").asLong()).isEqualTo(500);
		assertThat(r.get("tokensCacheEscrita").asLong()).isEqualTo(2000);
		assertThat(r.get("tokensCacheLeitura").asLong()).isEqualTo(10000);
		// (1000 * 3,00 + 500 * 15,00 + 2000 * 3,75 + 10000 * 0,30) / 1e6, em USD
		assertThat(r.get("custoEstimadoUsd").decimalValue()).isEqualByComparingTo("0.021");
		assertThat(r.at("/duracao/p50Ms").asDouble()).isEqualTo(2000.0);
		assertThat(r.at("/duracao/p90Ms").asDouble()).isEqualTo(2800.0);
	}

	@Test
	void semGeracoesZerosEDuracaoNula() throws Exception {
		var r = resumo(DIA);

		assertThat(r.get("geracoes").asLong()).isZero();
		assertThat(r.get("tokensEntrada").asLong()).isZero();
		assertThat(r.get("custoEstimadoUsd").decimalValue()).isEqualByComparingTo("0");
		assertThat(r.at("/duracao/p50Ms").isNull()).isTrue();
	}

	static final String SONNET = "claude-sonnet-4-6";
	static final OracaoProps.PrecoPorMilhao OUTRO = new OracaoProps.PrecoPorMilhao(new BigDecimal("1"), new BigDecimal("2"),
			new BigDecimal("3"), new BigDecimal("4"));

	OracaoProps comPrecos(java.util.Map<String, OracaoProps.PrecoPorMilhao> precos) {
		return new OracaoProps(props.anthropicApiKey(), props.anthropicUrl(), props.modelo(), props.maxTokens(), props.timeout(), precos);
	}

	static final FiltrosDeAdmin.Periodo MARCO = new FiltrosDeAdmin.Periodo(Instant.parse("2026-03-10T03:00:00Z"),
			Instant.parse("2026-03-11T03:00:00Z"));

	@Test
	void custoNuloSeFaltaQualquerPreco() {
		geracao("2026-03-10T15:00:00Z", 1, 1, 1, 1, 1);
		var p = props.precos().get(SONNET);
		var semUm = comPrecos(java.util.Map.of(SONNET, new OracaoProps.PrecoPorMilhao(p.entrada(), p.saida(), p.cacheEscrita(), null)));

		assertThat(new MetricasDaOracao(jdbcClient, semUm).resumo(MARCO).custoEstimadoUsd()).isNull();
		assertThat(new MetricasDaOracao(jdbcClient, comPrecos(null)).resumo(MARCO).custoEstimadoUsd()).isNull();
		assertThat(metricas.resumo(MARCO).custoEstimadoUsd()).isEqualByComparingTo(new BigDecimal("0.000022")); // (3 + 15 + 3,75 + 0,30) / 1e6, arredondado a 6 casas
	}

	@Test
	void cadaGeracaoPagaOPrecoDoSeuModelo() {
		geracao(SONNET, "2026-03-10T15:00:00Z", 1_000_000, 0, 0, 0, 1);
		geracao("modelo-b", "2026-03-10T16:00:00Z", 1_000_000, 1_000_000, 0, 0, 1);
		var precos = java.util.Map.of(SONNET, props.precos().get(SONNET), "modelo-b", OUTRO);

		var r = new MetricasDaOracao(jdbcClient, comPrecos(precos)).resumo(MARCO);

		assertThat(r.geracoes()).isEqualTo(2);
		assertThat(r.tokensEntrada()).isEqualTo(2_000_000);
		assertThat(r.custoEstimadoUsd()).isEqualByComparingTo("6.00"); // 3,00 (sonnet) + 1,00 + 2,00 (modelo-b)
	}

	@Test
	void modeloSemPrecoAnulaOCustoTotal() {
		geracao(SONNET, "2026-03-10T15:00:00Z", 10, 10, 0, 0, 1);
		geracao("modelo-sem-preco", "2026-03-10T16:00:00Z", 10, 10, 0, 0, 1);

		var r = metricas.resumo(MARCO);

		assertThat(r.geracoes()).isEqualTo(2);
		assertThat(r.custoEstimadoUsd()).isNull();
	}

	@Test
	void precosDoApplicationYml() {
		var p = props.precos().get(SONNET);
		assertThat(p.entrada()).isEqualByComparingTo("3.00");
		assertThat(p.saida()).isEqualByComparingTo("15.00");
		assertThat(p.cacheEscrita()).isEqualByComparingTo("3.75");
		assertThat(p.cacheLeitura()).isEqualByComparingTo("0.30");
	}

	@Test
	void filtroInvalidoEh400() throws Exception {
		var nav = nav(Papel.ADMIN);
		assertThat(nav.get("/api/admin/metricas/oracao?de=amanha").statusCode()).isEqualTo(400);
		assertThat(nav.get("/api/admin/metricas/oracao?de=2026-03-11&ate=2026-03-10").statusCode()).isEqualTo(400);
	}
}
