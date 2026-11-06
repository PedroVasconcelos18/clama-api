package me.clama.api.pedido;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** PedidoStatus (clama/orders/models.py): o valor gravado e o do Django. */
public enum StatusPedido {
    AGUARDANDO_PAGAMENTO("aguardando_pagamento"),
    AGUARDANDO_CONFIRMACAO_EMAIL("aguardando_confirmacao_email"),
    PAGO("pago"),
    GERANDO_ORACAO("gerando_oracao"),
    ORACAO_GERADA("oracao_gerada"),
    ENVIADA("enviada"),
    AGUARDANDO_REENVIO("aguardando_reenvio"),
    ERRO("erro");

    private final String valor;

    StatusPedido(String valor) {
        this.valor = valor;
    }

    public String valor() {
        return valor;
    }

    public static StatusPedido de(String valor) {
        for (StatusPedido s : values()) if (s.valor.equals(valor)) return s;
        throw new IllegalArgumentException("status desconhecido: " + valor);
    }

    @Converter
    public static class Conversor implements AttributeConverter<StatusPedido, String> {
        @Override
        public String convertToDatabaseColumn(StatusPedido s) {
            return s == null ? null : s.valor;
        }

        @Override
        public StatusPedido convertToEntityAttribute(String v) {
            return v == null ? null : de(v);
        }
    }
}
