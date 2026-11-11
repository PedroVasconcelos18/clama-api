package me.clama.pedido.web;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import me.clama.infra.web.FiltrosDeAdmin;
import me.clama.pedido.interno.MetricasDePedido;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Metricas de pedido (ADMIN, pela cadeia padrao). Dias de Sao Paulo, inclusivos. */
@RestController
@RequestMapping("/api/admin/metricas/pedidos")
class MetricasController {

	private final MetricasDePedido metricas;
	private final Clock relogio;

	MetricasController(MetricasDePedido metricas, Clock relogio) {
		this.metricas = metricas;
		this.relogio = relogio;
	}

	@GetMapping
	MetricasDePedido.Resumo resumo(
			@Parameter(description = "Dia inicial AAAA-MM-DD (Sao Paulo). Padrao: dia 1 do mes.") @RequestParam(required = false) @Size(max = 10) String de,
			@Parameter(description = "Dia final AAAA-MM-DD (Sao Paulo), inclusivo. Padrao: hoje.") @RequestParam(required = false) @Size(max = 10) String ate) {
		return metricas.resumo(FiltrosDeAdmin.periodoDoMes(de, ate, relogio));
	}
}
