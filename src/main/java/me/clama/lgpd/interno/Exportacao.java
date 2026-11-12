package me.clama.lgpd.interno;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.clama.blog.BlogApi;
import me.clama.conta.ContaApi;
import me.clama.freemium.FreemiumApi;
import me.clama.infra.auditoria.Acao;
import me.clama.infra.auditoria.Auditoria;
import me.clama.infra.cripto.HmacDeIdentificador;
import me.clama.infra.web.ErroDeNegocio;
import me.clama.pagamento.PagamentoApi;
import me.clama.pedido.PedidoApi;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exporta tudo do titular, decifrado (D11). A auditoria leva so as contagens: o e-mail nunca vai para ela, para o log
 * nem para o Sentry. Nunca logar o resultado.
 */
@Service
public class Exportacao {

	static final String NAO_ENCONTRADO = "Não encontramos dados para esse e-mail.";

	/** Para quem os dados vao (pergunta 2 da onda 7b): fixo, e a Politica de Privacidade que o espelha. */
	static final List<Map<String, Object>> COMPARTILHAMENTO = List.of(
			destino("Mercado Pago", "Processar o pagamento Pix da doação.", List.of("nome", "e-mail", "CPF ou CNPJ")),
			destino("Anthropic", "Gerar a oração a partir do pedido.", List.of("nome", "sexo", "idade", "texto do pedido de oração")),
			destino("Resend", "Enviar a oração e os e-mails do serviço.", List.of("nome", "e-mail", "texto da oração")));

	private static Map<String, Object> destino(String destinatario, String finalidade, List<String> dados) {
		var m = new LinkedHashMap<String, Object>();
		m.put("destinatario", destinatario);
		m.put("finalidade", finalidade);
		m.put("dados", dados);
		return java.util.Collections.unmodifiableMap(m);
	}

	private final BuscaDoTitular busca;
	private final ContaApi conta;
	private final PedidoApi pedido;
	private final PagamentoApi pagamento;
	private final BlogApi blog;
	private final FreemiumApi freemium;
	private final HmacDeIdentificador hmac;
	private final Auditoria auditoria;
	private final Clock relogio;

	Exportacao(BuscaDoTitular busca, ContaApi conta, PedidoApi pedido, PagamentoApi pagamento, BlogApi blog,
			FreemiumApi freemium, HmacDeIdentificador hmac, Auditoria auditoria, Clock relogio) {
		this.busca = busca;
		this.conta = conta;
		this.pedido = pedido;
		this.pagamento = pagamento;
		this.blog = blog;
		this.freemium = freemium;
		this.hmac = hmac;
		this.auditoria = auditoria;
		this.relogio = relogio;
	}

	/** Nada achado: 404 {@code titular_nao_encontrado}. */
	@Transactional
	public Map<String, Object> exportar(Long autorId, String email) {
		var achado = busca.achar(email);
		if (achado.vazio()) {
			throw new ErroDeNegocio(HttpStatus.NOT_FOUND, "titular_nao_encontrado", "Titular não encontrado.", NAO_ENCONTRADO);
		}
		var contas = new ArrayList<Map<String, Object>>();
		long comentarios = 0, curtidas = 0;
		Map<String, Object> blogDoTitular = null;
		for (var c : achado.contas()) {
			contas.add(conta.exportar(c.id()));
			blogDoTitular = blog.exportar(c.id()); // no maximo uma conta: o e-mail canonico e unico
			comentarios += ((List<?>) blogDoTitular.get("comentarios")).size();
			curtidas += ((List<?>) blogDoTitular.get("curtidas")).size();
		}
		if (blogDoTitular == null) {
			blogDoTitular = new LinkedHashMap<>();
			blogDoTitular.put("comentarios", List.of());
			blogDoTitular.put("curtidas", List.of());
			blogDoTitular.put("banimento", null);
			blogDoTitular.put("historicoDeBanimentos", List.of());
		}
		var pedidos = pedidosCompletos(achado.pedidos());

		var corpo = new LinkedHashMap<String, Object>();
		corpo.put("geradoEm", relogio.instant());
		corpo.put("email", email.strip());
		corpo.put("contas", contas);
		corpo.put("pedidos", pedidos);
		corpo.put("blog", blogDoTitular);
		corpo.put("freemium", Map.of("bloqueado", freemium.bloqueado(hmac.email(email), pedido.cpfHmacs(achado.pedidos()))));
		corpo.put("compartilhamento", COMPARTILHAMENTO);

		var contagens = new LinkedHashMap<String, Object>();
		contagens.put("contas", contas.size());
		contagens.put("pedidos", pedidos.size());
		contagens.put("comentarios", comentarios);
		contagens.put("curtidas", curtidas);
		auditoria.registrar(autorId, "lgpd_exportacao", UUID.randomUUID().toString(), Acao.ADICAO, contagens);
		return corpo;
	}

	/** O pedido, mais o pagamento (pagamento) e as confirmacoes do link (freemium), que sao de outros modulos. */
	private List<Map<String, Object>> pedidosCompletos(List<UUID> ids) {
		var pedidos = pedido.exportar(ids);
		var pagamentos = pagamento.pagamentos(ids);
		var confirmacoes = freemium.confirmacoes(ids);
		for (var p : pedidos) {
			var id = (UUID) p.get("id");
			var pg = pagamentos.get(id);
			Map<String, Object> pagamentoJson = null;
			if (pg != null) {
				pagamentoJson = new LinkedHashMap<>();
				pagamentoJson.put("confirmadoEm", pg.confirmadoEm());
				pagamentoJson.put("idNoMercadoPago", pg.mpPagamentoId());
			}
			p.put("pagamento", pagamentoJson);
			p.put("confirmacoesFreemium", confirmacoes.getOrDefault(id, List.of()));
		}
		return pedidos;
	}

	/** Nome do arquivo: so a hora (SP), nunca o e-mail. */
	public static String nomeDoArquivo(Instant agora) {
		return "lgpd_" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
				.format(agora.atZone(java.time.ZoneId.of("America/Sao_Paulo"))) + ".json";
	}
}
