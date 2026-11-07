package me.clama.api.pedido;

import java.util.UUID;

/** So o que o gate de pendente do freemium compara (ja decifrado pelo conversor); sem carregar o Pedido inteiro. */
public record IdentificadoresPendentes(UUID id, String email, String telefone, String cpfCnpj) {
}
