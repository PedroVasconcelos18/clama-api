package me.clama.pagamento.interno;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.UUID;

/** Pix vigente de um pedido (D16). Vencida, a mesma linha vira a cobranca seguinte ({@code n + 1}). */
@Entity
public class Cobranca {

	@Id
	private UUID pedidoId;
	private int n;
	private long mpPagamentoId;
	@Column(name = "pix_copia_e_cola")
	private String pixCopiaECola;
	private String pixQrBase64;
	private Instant expiraEm;
	private Instant criadaEm;

	protected Cobranca() {}

	static Cobranca nova(UUID pedidoId, MercadoPago.Pix pix, Instant expiraEm, Instant agora) {
		var c = new Cobranca();
		c.pedidoId = pedidoId;
		c.renovar(pix, expiraEm, agora);
		return c;
	}

	void renovar(MercadoPago.Pix pix, Instant expiraEm, Instant agora) {
		n++;
		mpPagamentoId = pix.id();
		pixCopiaECola = pix.copiaECola();
		pixQrBase64 = pix.qrBase64();
		this.expiraEm = expiraEm;
		criadaEm = agora;
	}

	boolean vigente(Instant agora) {
		return expiraEm.isAfter(agora);
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
}
