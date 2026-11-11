package me.clama.freemium.interno;

import java.util.UUID;
import me.clama.catalogo.CatalogoApi;
import me.clama.conta.ContaApi;
import me.clama.infra.cripto.HmacDeIdentificador;
import me.clama.infra.fila.Fila;
import me.clama.infra.web.ErroDeNegocio;
import me.clama.pedido.NovoPedido;
import me.clama.pedido.PedidoApi;
import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Portoes de banco do submit (D6, 5 a 10), depois do que o controller ja checou (campos, limite, Turnstile, e-mail
 * descartavel), e a criacao: pedido e job {@code confirmacao-freemium/<id>} na mesma transacao. So passa por
 * {@link PedidoApi} e {@link ContaApi}: as tabelas de pedido e usuario nao sao lidas aqui.
 */
@Service
public class PedidoFreemium {

	static final String JA_TEM_CONTA = "Você já tem conta com a gente. Faça login pra fazer um novo pedido.";
	static final String EM_ANDAMENTO = "Você já tem um pedido aguardando confirmação. Confira seu e-mail para finalizar — se não achar, olha na aba de spam.";
	static final String JA_USOU = "Você já recebeu sua oração gratuita. Sempre que quiser, pode fazer um novo pedido pela página principal.";

	private final PedidoApi pedidos;
	private final ContaApi contas;
	private final CatalogoApi catalogo;
	private final Bloqueios bloqueios;
	private final HmacDeIdentificador hmac;
	private final Fila fila;
	private final JobConfirmacaoFreemium job;
	private final FreemiumProps props;
	private final Clock relogio;

	PedidoFreemium(PedidoApi pedidos, ContaApi contas, CatalogoApi catalogo, Bloqueios bloqueios, HmacDeIdentificador hmac,
			Fila fila, JobConfirmacaoFreemium job, FreemiumProps props, Clock relogio) {
		this.pedidos = pedidos;
		this.contas = contas;
		this.catalogo = catalogo;
		this.bloqueios = bloqueios;
		this.hmac = hmac;
		this.fila = fila;
		this.job = job;
		this.props = props;
		this.relogio = relogio;
	}

	/** {@code ip} nulo (nao gravado) nao passa pelo limiar de IP. Erro em qualquer ponto desfaz tudo, inclusive o job. */
	@Transactional
	public UUID submeter(NovoPedido n, String ip) {
		var emailHmac = hmac.email(n.email());
		var cpfHmac = hmac.cpf(n.cpfCnpj());
		var agora = relogio.instant();
		if (contas.existe(n.email()) || pedidos.cpfTemConta(cpfHmac)) { // 5
			throw new ErroDeNegocio(HttpStatus.CONFLICT, "conta_existente", "Já existe uma conta para estes dados", JA_TEM_CONTA);
		}
		if (pedidos.existePendenteDeConfirmacao(emailHmac, cpfHmac, agora.minus(props.janelaPendente()))) { // 6
			throw new ErroDeNegocio(HttpStatus.CONFLICT, "pedido_em_andamento", "Já há um pedido aguardando confirmação", EM_ANDAMENTO);
		}
		if (bloqueios.existe(emailHmac, cpfHmac)) { // 7
			throw jaUsado();
		}
		var ipHmac = hmac.ip(ip);
		if (ipHmac != null && bloqueios.contarPorIp(ipHmac, agora.minus(props.janelaIp())) >= props.limiarIp()) { // 8
			throw jaUsado();
		}
		catalogo.planoGratuito(); // 9: 503 freemium_indisponivel se faltar; explicito para falhar antes de criar o pedido e o job
		var id = pedidos.criarFreemium(n, ip); // 10
		fila.enfileirar(job, job.instancia(id), id, agora);
		return id;
	}

	private static ErroDeNegocio jaUsado() {
		return new ErroDeNegocio(HttpStatus.CONFLICT, "freemium_ja_usado", "Oração gratuita já utilizada", JA_USOU);
	}
}
