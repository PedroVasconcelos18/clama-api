package me.clama.api.pedido;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** CanalEntrega (clama/orders/models.py). */
public enum CanalEntrega {
    EMAIL("email"),
    WHATSAPP("whatsapp");

    private final String valor;

    CanalEntrega(String valor) {
        this.valor = valor;
    }

    public String valor() {
        return valor;
    }

    @Converter
    public static class Conversor implements AttributeConverter<CanalEntrega, String> {
        @Override
        public String convertToDatabaseColumn(CanalEntrega c) {
            return c == null ? null : c.valor;
        }

        @Override
        public CanalEntrega convertToEntityAttribute(String v) {
            if (v == null) return null;
            for (CanalEntrega c : CanalEntrega.values()) if (c.valor.equals(v)) return c;
            throw new IllegalArgumentException("canal desconhecido: " + v);
        }
    }
}
