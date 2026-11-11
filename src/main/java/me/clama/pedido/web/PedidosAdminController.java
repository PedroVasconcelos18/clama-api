package me.clama.pedido.web;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import me.clama.catalogo.CatalogoApi;
import me.clama.pedido.CorrecaoDeDados;
import me.clama.pedido.HistoricoDePagamento;
import me.clama.pedido.PedidoNaoEncontradoException;
import me.clama.pedido.interno.AdminDePedidos;
import me.clama.pedido.interno.AdminDePedidos.Filtros;
import me.clama.pedido.interno.Pedido;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Pedidos no admin (ADMIN, pela cadeia padrao): lista, detalhe e correcao de dados (D22: sem status, plano nem valor). */
@RestController
@RequestMapping("/api/admin/pedidos")
class PedidosAdminController {

	/** Nulo = deixa como esta. */
	record CorrecaoDoPedido(@Size(min = 2, max = 120) String nome, @Email @Size(max = 254) String email,
			@Size(max = 20) String telefone, @Min(1) @Max(120) Short idade,
			@Size(max = 20) @Pattern(regexp = "feminino|masculino|nao_informado") String sexo,
			@Size(max = 2000) String pedidoOracao) {}

	private final AdminDePedidos servico;
	private final CatalogoApi catalogo;
	private final HistoricoDePagamento historico;

	PedidosAdminController(AdminDePedidos servico, CatalogoApi catalogo, HistoricoDePagamento historico) {
		this.servico = servico;
		this.catalogo = catalogo;
		this.historico = historico;
	}

	/** Ordem fixa (criadoEm decrescente). Datas {@code de}/{@code ate} sao dias de Sao Paulo, inclusivos. */
	@GetMapping
	PagedModel<PedidoAdminItem> listar(
			@Parameter(description = "Um ou mais status separados por virgula.") @RequestParam(required = false) @Size(max = 200) String status,
			@Parameter(description = "Dia inicial AAAA-MM-DD (Sao Paulo).") @RequestParam(required = false) @Size(max = 10) String de,
			@Parameter(description = "Dia final AAAA-MM-DD (Sao Paulo), inclusivo.") @RequestParam(required = false) @Size(max = 10) String ate,
			@RequestParam(required = false) @Size(max = 10) String gratuito,
			@RequestParam(required = false) @Size(max = 20) String usuarioId,
			@Parameter(description = AdminDePedidos.DICA_DE_BUSCA) @RequestParam(required = false) @Size(max = 254) String q,
			@ParameterObject @PageableDefault(size = 20) Pageable pagina) {
		var fixa = PageRequest.of(pagina.getPageNumber(), pagina.getPageSize(), Sort.by(Sort.Direction.DESC, "criadoEm", "id"));
		var pag = servico.listar(new Filtros(status, de, ate, gratuito, usuarioId, q), fixa);
		var planos = catalogo.planos(pag.getContent().stream().map(Pedido::getPlanoId).distinct().toList());
		return new PagedModel<>(pag.map(p -> {
			var plano = planos.get(p.getPlanoId());
			return new PedidoAdminItem(p.getId(), p.getStatus(), p.getValorCentavos(), plano == null ? null : plano.nome(),
					p.getCriadoEm(), p.isGratuito(), p.getNome());
		}));
	}

	@GetMapping("/{id}")
	PedidoAdminDetalhe detalhar(@PathVariable String id) {
		return detalhe(uuid(id));
	}

	@PatchMapping("/{id}")
	PedidoAdminDetalhe corrigir(@PathVariable String id, @Valid @RequestBody CorrecaoDoPedido c, Authentication auth) {
		var uuid = uuid(id);
		servico.corrigir(Long.valueOf(auth.getName()), uuid,
				new CorrecaoDeDados(c.nome() == null ? null : c.nome().strip(), c.email() == null ? null : c.email().strip(),
						c.telefone(), c.idade(), c.sexo(), c.pedidoOracao()));
		return detalhe(uuid);
	}

	private PedidoAdminDetalhe detalhe(UUID id) {
		var p = servico.detalhar(id);
		var plano = catalogo.planos(List.of(p.getPlanoId())).get(p.getPlanoId());
		var instituicao = p.getInstituicaoId() == null ? null : catalogo.instituicoes(List.of(p.getInstituicaoId())).get(p.getInstituicaoId());
		var h = historico.de(id);
		return new PedidoAdminDetalhe(p.getId(), p.getCriadoEm(), p.getAtualizadoEm(), p.getStatus(), p.isGratuito(),
				p.getValorCentavos(), p.getUsuarioId(), p.getNome(), p.getEmail(), p.getTelefone(), p.getIdade(), p.getSexo(),
				p.getPedidoOracao(), p.getOracao(),
				plano == null ? null : new PedidoAdminDetalhe.Plano(plano.id(), plano.nome(), plano.valorCentavos()),
				instituicao == null ? null : new PedidoAdminDetalhe.Instituicao(instituicao.id(), instituicao.nome()),
				p.getTentativas(), p.getUltimoErro(), h.cobrancas(), h.repasse(), h.eventos());
	}

	private static UUID uuid(String s) {
		try {
			if (s.length() == 36) return UUID.fromString(s);
		} catch (IllegalArgumentException e) {
			// cai no 404
		}
		throw new PedidoNaoEncontradoException();
	}
}
