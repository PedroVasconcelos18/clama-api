package me.clama.api.pagamento;

/** CobrancaResult do Django: id do pagamento no Mercado Pago e o Pix (copia-e-cola e PNG base64). */
public record CobrancaPix(String providerPaymentId, String pixQrCode, String pixQrCodeBase64, String ticketUrl) {}
