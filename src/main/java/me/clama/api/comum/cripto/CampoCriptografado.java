package me.clama.api.comum.cripto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Coluna cifrada como o django-encrypted-model-fields. Valor ilegivel (texto puro antigo) volta cru, como no Django. */
@Converter
public class CampoCriptografado implements AttributeConverter<String, String> {
    // ponytail: holder estatico (o Hibernate instancia o converter); trocar por injecao se o Boot 4 injetar sem atrito.
    private static volatile Fernet fernet;

    public static void usar(Fernet f) {
        fernet = f;
    }

    @Override
    public String convertToDatabaseColumn(String claro) {
        return claro == null ? null : fernet.cifrar(claro);
    }

    @Override
    public String convertToEntityAttribute(String coluna) {
        if (coluna == null) {
            return null;
        }
        try {
            return fernet.decifrar(coluna);
        } catch (TokenInvalido e) {
            return coluna;
        }
    }
}
