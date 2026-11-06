package me.clama.api.pagamento;

/**
 * PaymentProviderError: falha de integracao com o Mercado Pago depois dos retries. upstreamStatus e null em erro de
 * rede/timeout ou resposta sem id/QR; upstreamBody e o corpo cru da resposta (nunca o token).
 */
public class ErroDoProvedor extends RuntimeException {
    private final Integer upstreamStatus;
    private final String upstreamBody;

    public ErroDoProvedor(String message, Integer upstreamStatus, String upstreamBody) {
        super(message);
        this.upstreamStatus = upstreamStatus;
        this.upstreamBody = upstreamBody;
    }

    public Integer upstreamStatus() {
        return upstreamStatus;
    }

    public String upstreamBody() {
        return upstreamBody;
    }
}
