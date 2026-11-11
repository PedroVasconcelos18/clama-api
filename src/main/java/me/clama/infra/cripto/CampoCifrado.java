package me.clama.infra.cripto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/** Coluna de texto cifrada: {@code @Convert(converter = CampoCifrado.class)}. O Hibernate o obtem do Spring (injecao da Cifra). */
@Converter
@Component
public class CampoCifrado implements AttributeConverter<String, String> {

	private final Cifra cifra;

	public CampoCifrado(Cifra cifra) {
		this.cifra = cifra;
	}

	@Override
	public String convertToDatabaseColumn(String claro) {
		return claro == null ? null : cifra.cifrar(claro);
	}

	@Override
	public String convertToEntityAttribute(String gravado) {
		return gravado == null ? null : cifra.decifrar(gravado);
	}
}
