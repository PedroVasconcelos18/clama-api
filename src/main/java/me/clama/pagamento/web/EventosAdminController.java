package me.clama.pagamento.web;

import jakarta.validation.constraints.Size;
import me.clama.pagamento.interno.AdminDePagamento;
import me.clama.pagamento.interno.AdminDePagamento.Reprocessado;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Eventos do Mercado Pago no admin (ADMIN, pela cadeia padrao). So RECEBIDO e ERRO se reprocessam. */
@RestController
@RequestMapping("/api/admin/eventos-mp")
class EventosAdminController {

	record EventoReprocessado(String resultado, EventoMpAdmin evento) {
		static EventoReprocessado de(Reprocessado r) {
			return new EventoReprocessado(r.resultado(), EventoMpAdmin.de(r.evento()));
		}
	}

	private final AdminDePagamento servico;

	EventosAdminController(AdminDePagamento servico) {
		this.servico = servico;
	}

	/** Ordem fixa (mais recente primeiro); {@code status}: RECEBIDO, PROCESSADO, IGNORADO ou ERRO. */
	@GetMapping
	PagedModel<EventoMpAdmin> listar(@RequestParam(required = false) @Size(max = 20) String status,
			@ParameterObject @PageableDefault(size = 20) Pageable pagina) {
		var fixa = PageRequest.of(pagina.getPageNumber(), pagina.getPageSize(), Sort.unsorted());
		return new PagedModel<>(servico.eventos(status, fixa).map(e -> EventoMpAdmin.de(e)));
	}

	@GetMapping("/{id}")
	EventoMpAdmin detalhar(@PathVariable long id) {
		return EventoMpAdmin.de(servico.evento(id));
	}

	@PostMapping("/{id}/reprocessamento")
	EventoReprocessado reprocessar(@PathVariable long id, Authentication auth) {
		return EventoReprocessado.de(servico.reprocessar(Long.valueOf(auth.getName()), id));
	}
}
