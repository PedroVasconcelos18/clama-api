package me.clama.pedido;

/**
 * Chaves de bloqueio do pedido freemium confirmado, todas HMAC (nunca o valor em claro). {@code ip} e o HMAC do IP do
 * submit, ou {@code null} se nao foi gravado; {@code cpfHmac} idem se o pedido nao tem CPF.
 */
public record Identificadores(String emailHmac, String cpfHmac, String ip) {
}
