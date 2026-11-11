package me.clama.freemium.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import me.clama.freemium.interno.AdminDeBloqueios;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Bloqueios do freemium no admin (ADMIN, pela cadeia padrao de /api/admin/**). Sem adicionar nem editar. */
@RestController
@RequestMapping("/api/admin/freemium/bloqueios")
class BloqueiosAdminController {

	record Busca(@Size(max = 254) String email, @Size(max = 30) String cpfCnpj) {}

	private final AdminDeBloqueios servico;

	BloqueiosAdminController(AdminDeBloqueios servico) {
		this.servico = servico;
	}

	/** Ordem fixa: mais recente primeiro. */
	@GetMapping
	PagedModel<BloqueioAdmin> listar(@ParameterObject @PageableDefault(size = 20) Pageable pagina) {
		var fixa = PageRequest.of(pagina.getPageNumber(), pagina.getPageSize(), Sort.unsorted());
		return new PagedModel<>(servico.listar(fixa).map(BloqueioAdmin::de));
	}

	/** O valor em claro vai no corpo (nunca na URL, que vai para log); hash so no servidor. */
	@PostMapping("/busca")
	List<BloqueioAdmin> buscar(@Valid @RequestBody Busca busca) {
		return servico.buscar(busca.email(), busca.cpfCnpj()).stream().map(BloqueioAdmin::de).toList();
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void excluir(@PathVariable long id, Authentication auth) {
		servico.excluir(Long.parseLong(auth.getName()), id);
	}
}
