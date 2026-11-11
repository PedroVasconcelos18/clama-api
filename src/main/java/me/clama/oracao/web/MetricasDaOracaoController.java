package me.clama.oracao.web;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import me.clama.infra.web.FiltrosDeAdmin;
import me.clama.oracao.interno.MetricasDaOracao;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Tokens, custo estimado e duracao das geracoes da Claude (ADMIN, pela cadeia padrao). Dias de Sao Paulo. */
@RestController
@RequestMapping("/api/admin/metricas/oracao")
class MetricasDaOracaoController {

	private final MetricasDaOracao metricas;
	private final Clock relogio;

	MetricasDaOracaoController(MetricasDaOracao metricas, Clock relogio) {
		this.metricas = metricas;
		this.relogio = relogio;
	}

	@GetMapping
	MetricasDaOracao.Resumo resumo(
			@Parameter(description = "Dia inicial AAAA-MM-DD (Sao Paulo). Padrao: dia 1 do mes.") @RequestParam(required = false) @Size(max = 10) String de,
			@Parameter(description = "Dia final AAAA-MM-DD (Sao Paulo), inclusivo. Padrao: hoje.") @RequestParam(required = false) @Size(max = 10) String ate) {
		return metricas.resumo(FiltrosDeAdmin.periodoDoMes(de, ate, relogio));
	}
}
