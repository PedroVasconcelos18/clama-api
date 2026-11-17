package me.clama.pedido.interno;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import me.clama.catalogo.CatalogoApi;
import me.clama.catalogo.PlanoResumo;
import me.clama.infra.cripto.HmacDeIdentificador;
import me.clama.infra.web.ErroDeCampo;
import me.clama.infra.formato.Dinheiro;
import me.clama.infra.web.ErroDeNegocio;
import me.clama.pedido.GeracaoSolicitada;
import me.clama.pedido.NovoPedido;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cria o pedido: regras de plano e valor (D6, D7), consentimento e, no gratuito, o evento de geracao. A conta do doador
 * anonimo nao nasce aqui: e criada na confirmacao do pagamento ({@code PedidoApi.confirmarPagamento}).
 */
@Service
public class CriacaoDePedido {

	/** Versao vigente da politica de privacidade que o consentimento registra. */
	public static final String POLITICA_VERSAO = "1";

	private final PedidoRepository pedidos;
	private final CatalogoApi catalogo;
	private final HmacDeIdentificador hmac;
	private final Clock relogio;
	private final ApplicationEventPublisher eventos;
	private final long valorMinimoCentavos;

	CriacaoDePedido(PedidoRepository pedidos, CatalogoApi catalogo, HmacDeIdentificador hmac, Clock relogio,
			ApplicationEventPublisher eventos, @Value("${clama.pagamento.valor-minimo-centavos:100}") long valorMinimoCentavos) {
		this.pedidos = pedidos;
		this.catalogo = catalogo;
		this.hmac = hmac;
		this.relogio = relogio;
		this.eventos = eventos;
		this.valorMinimoCentavos = valorMinimoCentavos;
	}

	/** Pedido pago de quem tem conta: plano explicito (valor pelo menos o do plano) ou inferido pelo valor. */
	@Transactional
	public Pedido criarPago(Long usuarioId, NovoPedido n, String ip) {
		return pago(usuarioId, n, n.plano(), ip);
	}

	/** Doacao anonima: o plano do corpo e ignorado, sempre inferido pelo valor; sem dono ate o pagamento. */
	@Transactional
	public Pedido criarDoacao(NovoPedido n, String ip) {
		return pago(null, n, null, ip);
	}

	/** Gratuito de quem tem conta: plano gratuito (503 se faltar), nasce PAGO e pede a geracao na mesma transacao. */
	@Transactional
	public Pedido criarGratuito(Long usuarioId, NovoPedido n, String ip) {
		var plano = catalogo.planoGratuito();
		var agora = relogio.instant();
		if (pedidos.existsByUsuarioIdAndGratuitoTrueAndCriadoEmAfter(usuarioId, agora.minus(Duration.ofHours(24)))) {
			throw new ErroDeNegocio(HttpStatus.TOO_MANY_REQUESTS, "gratuito_diario", "Limite diário de orações gratuitas.",
					"Você já recebeu uma oração gratuita nas últimas 24 horas. Volte amanhã para pedir outra — estamos com você.");
		}
		var p = pedidos.saveAndFlush(Pedido.gratuito(dados(usuarioId, plano.id(), null, 0, n, ip), hmac, agora));
		eventos.publishEvent(new GeracaoSolicitada(p.getId()));
		return p;
	}

	/** Freemium: plano gratuito (503 se faltar), AGUARDANDO_CONFIRMACAO_EMAIL; sem conta e sem evento. */
	@Transactional
	public Pedido criarFreemium(NovoPedido n, String ip) {
		var plano = catalogo.planoGratuito();
		return pedidos.saveAndFlush(Pedido.freemium(dados(null, plano.id(), null, 0, n, ip), hmac, relogio.instant()));
	}

	private Pedido pago(Long usuarioId, NovoPedido n, Long planoEscolhido, String ip) {
		var erros = new ArrayList<ErroDeCampo>();
		PlanoResumo plano = null;
		if (n.valorCentavos() == null) {
			erros.add(new ErroDeCampo("valorCentavos", "Informe o valor da oferta."));
		} else if (n.valorCentavos() < valorMinimoCentavos) {
			erros.add(new ErroDeCampo("valorCentavos",
					"O valor mínimo é " + Dinheiro.reais(valorMinimoCentavos) + " — qualquer oferta acima ajuda o Clama."));
		}
		if (planoEscolhido != null) {
			plano = catalogo.planoAtivo(planoEscolhido).orElse(null);
			if (plano == null) {
				erros.add(new ErroDeCampo("plano", "Esse plano não está disponível no momento."));
			} else if (erros.isEmpty() && n.valorCentavos() < plano.valorCentavos()) {
				erros.add(new ErroDeCampo("valorCentavos", "O valor mínimo para este plano é " + Dinheiro.reais(plano.valorCentavos()) + "."));
			}
		}
		if (n.instituicao() == null) {
			erros.add(new ErroDeCampo("instituicao", "Escolha a instituição que vai receber parte da sua contribuição."));
		} else if (catalogo.instituicaoAtiva(n.instituicao()).isEmpty()) {
			erros.add(new ErroDeCampo("instituicao", "Não encontramos essa instituição. Escolha uma da lista, por favor."));
		}
		if (!erros.isEmpty()) throw dadosInvalidos(erros);
		if (plano == null) plano = catalogo.inferirPlano(n.valorCentavos());
		return pedidos.saveAndFlush(Pedido.novo(dados(usuarioId, plano.id(), n.instituicao(), n.valorCentavos(), n, ip), hmac, relogio.instant()));
	}

	private Pedido.Dados dados(Long usuarioId, Long planoId, Long instituicaoId, long valor, NovoPedido n, String ip) {
		var cpf = n.cpfCnpj().replaceAll("\\D", ""); // so digitos: o que o provedor de pagamento espera e o HMAC usa
		return new Pedido.Dados(usuarioId, planoId, instituicaoId, valor, n.nome(), n.email(), cpf, n.telefone(), n.idade(),
				n.sexo(), n.pedidoOracao(), relogio.instant(), ip, POLITICA_VERSAO, n.tema());
	}

	private static ErroDeNegocio dadosInvalidos(List<ErroDeCampo> erros) {
		return new ErroDeNegocio(HttpStatus.BAD_REQUEST, "dados_invalidos", "Dados inválidos.", null, erros);
	}
}
