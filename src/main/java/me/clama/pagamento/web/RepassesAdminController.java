package me.clama.pagamento.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import me.clama.catalogo.CatalogoApi;
import me.clama.pagamento.interno.AdminDePagamento;
import me.clama.pagamento.interno.AdminDePagamento.Repasse;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Repasses no admin (ADMIN, pela cadeia padrao): lista e "marcar transferido". Sem PII. */
@RestController
@RequestMapping("/api/admin/repasses")
class RepassesAdminController {

	private final AdminDePagamento servico;
	private final CatalogoApi catalogo;

	RepassesAdminController(AdminDePagamento servico, CatalogoApi catalogo) {
		this.servico = servico;
		this.catalogo = catalogo;
	}

	/** Ordem fixa (mais recente primeiro). {@code de}/{@code ate}: dias de Sao Paulo, inclusivos, sobre a data do repasse. */
	@GetMapping
	PagedModel<RepasseAdmin> listar(@RequestParam(required = false) @Size(max = 20) String instituicaoId,
			@RequestParam(required = false) @Size(max = 20) String status, @RequestParam(required = false) @Size(max = 10) String de,
			@RequestParam(required = false) @Size(max = 10) String ate,
			@ParameterObject @PageableDefault(size = 20) Pageable pagina) {
		var fixa = PageRequest.of(pagina.getPageNumber(), pagina.getPageSize(), Sort.unsorted());
		var pag = servico.repasses(instituicaoId, status, de, ate, fixa);
		var nomes = catalogo.instituicoes(pag.getContent().stream().map(Repasse::instituicaoId).distinct().toList());
		return new PagedModel<>(pag.map(r -> RepasseAdmin.de(r, nome(nomes, r))));
	}

	/** Prova da transferencia (id do Pix, numero do recibo...): obrigatoria. */
	record Transferencia(@NotBlank @Size(max = 120) String comprovante) {}

	/** REGISTRADO vira TRANSFERIDO; repetir e no-op; CANCELADO responde 409. */
	@PostMapping("/{id}/transferencia")
	RepasseAdmin transferir(@PathVariable long id, @Valid @RequestBody Transferencia corpo, Authentication auth) {
		var r = servico.transferir(Long.valueOf(auth.getName()), id, corpo.comprovante());
		return RepasseAdmin.de(r, nome(catalogo.instituicoes(List.of(r.instituicaoId())), r));
	}

	private static String nome(java.util.Map<Long, me.clama.catalogo.InstituicaoResumo> nomes, Repasse r) {
		var i = nomes.get(r.instituicaoId());
		return i == null ? null : i.nome();
	}
}
