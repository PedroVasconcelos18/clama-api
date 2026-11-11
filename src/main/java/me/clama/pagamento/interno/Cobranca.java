package me.clama.pagamento.interno;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.UUID;

/** Uma cobranca Pix de um pedido (a n-esima). Nunca e apagada: cancelada, ganha {@code canceladaEm}. */
@Entity
public class Cobranca {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	private UUID pedidoId;
	private int n;
	private long mpPagamentoId;
	@Column(name = "pix_copia_e_cola")
	private String pixCopiaECola;
	private String pixQrBase64;
	private Instant expiraEm;
	private Instant criadaEm;
	private Instant canceladaEm;

	protected Cobranca() {}

	static Cobranca nova(UUID pedidoId, int n, MercadoPago.Pix pix, Instant agora) {
		var c = new Cobranca();
		c.pedidoId = pedidoId;
		c.n = n;
		c.mpPagamentoId = pix.id();
		c.pixCopiaECola = pix.copiaECola();
		c.pixQrBase64 = pix.qrBase64();
		c.expiraEm = pix.expiraEm();
		c.criadaEm = agora;
		return c;
	}

	boolean vigente(Instant agora) {
		return canceladaEm == null && expiraEm.isAfter(agora);
	}

	void cancelar(Instant agora) {
		canceladaEm = agora;
	}

	/** Chave de idempotencia da n-esima cobranca: a primeira e a do sistema antigo (INV:93). */
	static String chave(UUID pedidoId, int n) {
		return n == 1 ? "clama-pedido-" + pedidoId : "clama-pedido-" + pedidoId + "-" + n;
	}

	public UUID getPedidoId() { return pedidoId; }
	public int getN() { return n; }
	public long getMpPagamentoId() { return mpPagamentoId; }
	public String getPixCopiaECola() { return pixCopiaECola; }
	public String getPixQrBase64() { return pixQrBase64; }
	public Instant getExpiraEm() { return expiraEm; }
	public Instant getCanceladaEm() { return canceladaEm; }
}
