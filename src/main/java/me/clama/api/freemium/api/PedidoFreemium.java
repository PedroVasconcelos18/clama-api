package me.clama.api.freemium.api;

/** validated_data do submit freemium, ja normalizado (consent_aceito e sempre true, entao nao entra). */
public record PedidoFreemium(String nome, String email, String telefone, String cpfCnpj, Integer idade, String sexo,
        String pedidoOracao, String turnstileToken, String deviceHash) {}
