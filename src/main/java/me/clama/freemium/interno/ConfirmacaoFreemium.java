package me.clama.freemium.interno;

import java.util.UUID;
import me.clama.infra.web.ErroDeNegocio;
import me.clama.pedido.Identificadores;
import me.clama.pedido.PedidoApi;
import me.clama.pedido.PedidoNaoEncontradoException;
import me.clama.pedido.TransicaoInvalidaException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;

/**
 * Saga da confirmacao (D3, D4, D5): uma transacao so. Trava o link pelo hash, confirma o pedido (PAGO, conta, evento
 * da geracao), grava o bloqueio e gasta o link. Qualquer erro, inclusive o bloqueio que colide, desfaz tudo: sem conta,
 * pedido pendente, link valido e nenhum e-mail (o evento da geracao so age no commit; a conta nova nao manda e-mail:
 * o link de ativacao vai na oracao, e o token de senha da tela e emitido depois, pelo controller).
 */
@Service
public class ConfirmacaoFreemium {

	static final String INVALIDO = "Esse link não é mais válido. Faça um novo pedido.";
	static final String EXPIRADO = "Esse link expirou. Faça um novo pedido pra receber sua oração grátis.";

	private final Confirmacoes confirmacoes;
	private final PedidoApi pedidos;
	private final Bloqueios bloqueios;
	private final Clock relogio;
	private final FreemiumProps props;

	ConfirmacaoFreemium(Confirmacoes confirmacoes, PedidoApi pedidos, Bloqueios bloqueios, Clock relogio, FreemiumProps props) {
		this.props = props;
		this.confirmacoes = confirmacoes;
		this.pedidos = pedidos;
		this.bloqueios = bloqueios;
		this.relogio = relogio;
	}

	/** Devolve o id do pedido, ja PAGO. */
	@Transactional
	public UUID confirmar(String token) {
		var agora = relogio.instant();
		var link = confirmacoes.travarPorHash(Confirmacoes.hash(token)).orElseThrow(ConfirmacaoFreemium::invalido);
		if (link.usado()) throw invalido();
		if (link.vencido(agora)) {
			throw new ErroDeNegocio(HttpStatus.BAD_REQUEST, "link_expirado", "Link de confirmação expirado", EXPIRADO);
		}
		var ids = confirmar(link.pedidoId());
		// o limiar do IP tambem vale aqui: bloqueio so nasce na confirmacao, entao varios pedidos submetidos antes nao o veem
		if (ids.ip() != null && bloqueios.contarPorIp(ids.ip(), agora.minus(props.janelaIp())) >= props.limiarIp()) {
			throw jaUsado();
		}
		if (!bloqueios.inserir(ids.emailHmac(), ids.cpfHmac(), ids.ip(), agora)) {
			throw jaUsado();
		}
		confirmacoes.marcarUsado(link.id());
		return link.pedidoId();
	}

	private Identificadores confirmar(UUID pedidoId) {
		try {
			// D4: conta criada entre o submit e a confirmacao e vinculada mesmo inativa (o portao do submit ja recusa conta existente)
			return pedidos.confirmarEmail(pedidoId);
		} catch (TransicaoInvalidaException | PedidoNaoEncontradoException e) { // fora do estado: o mesmo "nao e mais valido"
			throw invalido();
		}
	}

	private static ErroDeNegocio jaUsado() {
		return new ErroDeNegocio(HttpStatus.CONFLICT, "freemium_ja_usado", "Oração gratuita já utilizada", PedidoFreemium.JA_USOU);
	}

	private static ErroDeNegocio invalido() {
		return new ErroDeNegocio(HttpStatus.BAD_REQUEST, "link_invalido", "Link de confirmação inválido", INVALIDO);
	}
}
