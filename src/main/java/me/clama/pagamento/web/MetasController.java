package me.clama.pagamento.web;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import me.clama.infra.seguranca.ClienteIp;
import me.clama.infra.seguranca.LimiteDeTaxa;
import me.clama.infra.seguranca.LimiteDeTaxa.Cota;
import me.clama.pagamento.interno.RepasseProps;
import me.clama.pagamento.interno.relatorio.Ciclo;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Arrecadado por instituicao no ciclo em curso (dia 28 ao 27, Sao Paulo): so repasses REGISTRADO ou TRANSFERIDO. */
@RestController
class MetasController {

	private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
	private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic();

	record Arrecadado(Long instituicaoId, long arrecadadoCentavos) {}

	record Metas(int percentual, LocalDate de, LocalDate ate, List<Arrecadado> instituicoes) {}

	private final JdbcClient jdbc;
	private final RepasseProps repasse;
	private final Clock relogio;
	private final LimiteDeTaxa limite;

	MetasController(JdbcClient jdbc, RepasseProps repasse, Clock relogio, LimiteDeTaxa limite) {
		this.jdbc = jdbc;
		this.repasse = repasse;
		this.relogio = relogio;
		this.limite = limite;
	}

	@GetMapping("/api/metas")
	ResponseEntity<Metas> metas(HttpServletRequest req) {
		limite.exigir(new Cota("catalogo:ip:" + ClienteIp.de(req), 300, Duration.ofMinutes(1)));
		var ciclo = Ciclo.emCurso(LocalDate.now(relogio.withZone(SAO_PAULO)));
		var lista = jdbc.sql("""
				select i.id, coalesce(sum(r.valor_centavos), 0) as total
				from instituicao i
				left join repasse r on r.instituicao_id = i.id and r.status in ('REGISTRADO', 'TRANSFERIDO')
				  and r.criado_em >= :inicio and r.criado_em < :fim
				where i.ativo
				group by i.id
				order by i.ordem, i.nome, i.id""")
				.param("inicio", ciclo.de().atStartOfDay(SAO_PAULO).toOffsetDateTime())
				.param("fim", ciclo.ate().plusDays(1).atStartOfDay(SAO_PAULO).toOffsetDateTime())
				.query((rs, n) -> new Arrecadado(rs.getLong("id"), rs.getLong("total"))).list();
		return ResponseEntity.ok().cacheControl(CACHE).body(new Metas(repasse.percentual(), ciclo.de(), ciclo.ate(), lista));
	}
}
