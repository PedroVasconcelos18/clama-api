package me.clama.pedido.interno;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import me.clama.infra.auditoria.Acao;
import me.clama.infra.auditoria.Auditoria;
import me.clama.infra.cripto.HmacDeIdentificador;
import me.clama.infra.web.FiltrosDeAdmin;
import me.clama.pedido.CorrecaoDeDados;
import me.clama.pedido.PedidoApi;
import me.clama.pedido.PedidoNaoEncontradoException;
import me.clama.pedido.StatusPedido;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Admin de pedidos. A lista filtra por colunas em claro e por HMAC (nada e decifrado para buscar). {@code q} (D4):
 * e-mail exato, CPF/CNPJ exato ou o comeco do id do pedido; nao ha busca por nome (esta cifrado). A correcao de dados
 * entra pela {@link PedidoApi}, com o pedido travado, e a auditoria grava so os nomes dos campos (D22).
 */
@Service
public class AdminDePedidos {

	private static final Pattern PREFIXO_DE_ID = Pattern.compile("[0-9a-fA-F-]{1,36}");
	private static final Pattern CPF_CNPJ = Pattern.compile("[0-9.\\-/]{11,18}");
	public static final String DICA_DE_BUSCA = "Busque pelo e-mail exato, pelo CPF/CNPJ exato ou pelo começo do id do pedido.";

	/** Filtros ja crus (texto) como vieram da query; a validacao e daqui. */
	public record Filtros(String status, String de, String ate, String gratuito, String usuarioId, String q) {}

	private final PedidoRepository pedidos;
	private final PedidoApi api;
	private final HmacDeIdentificador hmac;
	private final Auditoria auditoria;
	private final TransactionTemplate transacao;

	AdminDePedidos(PedidoRepository pedidos, PedidoApi api, HmacDeIdentificador hmac, Auditoria auditoria,
			TransactionTemplate transacao) {
		this.pedidos = pedidos;
		this.api = api;
		this.hmac = hmac;
		this.auditoria = auditoria;
		this.transacao = transacao;
	}

	public Page<Pedido> listar(Filtros f, Pageable pagina) {
		Specification<Pedido> filtro = (r, c, cb) -> cb.conjunction();
		var status = status(f.status());
		if (!status.isEmpty()) filtro = filtro.and((r, c, cb) -> r.get("status").in(status));
		var de = FiltrosDeAdmin.dia("de", f.de());
		var ate = FiltrosDeAdmin.dia("ate", f.ate());
		FiltrosDeAdmin.ordemDoPeriodo(de, ate);
		if (de != null) filtro = filtro.and((r, c, cb) -> cb.greaterThanOrEqualTo(r.get("criadoEm"), FiltrosDeAdmin.inicio(de)));
		if (ate != null) filtro = filtro.and((r, c, cb) -> cb.lessThan(r.get("criadoEm"), FiltrosDeAdmin.fimExclusivo(ate)));
		var gratuito = FiltrosDeAdmin.booleano("gratuito", f.gratuito());
		if (gratuito != null) filtro = filtro.and((r, c, cb) -> cb.equal(r.get("gratuito"), gratuito));
		var usuario = FiltrosDeAdmin.numero("usuarioId", f.usuarioId());
		if (usuario != null) filtro = filtro.and((r, c, cb) -> cb.equal(r.get("usuarioId"), usuario));
		if (f.q() != null && !f.q().isBlank()) filtro = filtro.and(busca(f.q().strip()));
		return pedidos.findAll(filtro, pagina);
	}

	private List<StatusPedido> status(String csv) {
		var lista = new ArrayList<StatusPedido>();
		if (csv == null || csv.isBlank()) return lista;
		for (var s : csv.split(",")) {
			try {
				lista.add(StatusPedido.valueOf(s.strip().toUpperCase(Locale.ROOT)));
			} catch (IllegalArgumentException e) {
				throw FiltrosDeAdmin.invalido("status", "Status desconhecido: " + Arrays.toString(StatusPedido.values()));
			}
		}
		return lista;
	}

	private Specification<Pedido> busca(String q) {
		var ehEmail = q.contains("@");
		var digitos = q.replaceAll("\\D", "").length();
		var ehCpf = CPF_CNPJ.matcher(q).matches() && (digitos == 11 || digitos == 14);
		var ehPrefixo = PREFIXO_DE_ID.matcher(q).matches();
		if (!ehEmail && !ehCpf && !ehPrefixo) throw FiltrosDeAdmin.invalido("q", DICA_DE_BUSCA);
		return (r, c, cb) -> {
			var ou = new ArrayList<Predicate>();
			if (ehEmail) ou.add(cb.equal(r.get("emailHmac"), hmac.email(q)));
			if (ehCpf) ou.add(cb.equal(r.get("cpfHmac"), hmac.cpf(q)));
			if (ehPrefixo) ou.add(cb.like(cb.function("str", String.class, r.get("id")), q.toLowerCase(Locale.ROOT) + "%")); // so hex e '-': sem curinga
			return cb.or(ou.toArray(Predicate[]::new));
		};
	}

	public Pedido detalhar(UUID id) {
		return pedidos.findById(id).orElseThrow(PedidoNaoEncontradoException::new);
	}

	/** Pedido travado, correcao e auditoria na mesma transacao; devolve os nomes dos campos que mudaram. */
	public List<String> corrigir(Long autorId, UUID id, CorrecaoDeDados correcao) {
		return transacao.execute(s -> {
			var campos = api.corrigirDados(id, correcao);
			if (!campos.isEmpty()) auditoria.registrar(autorId, "pedido", id.toString(), Acao.MUDANCA, Map.of("campos", campos));
			return campos;
		});
	}
}
