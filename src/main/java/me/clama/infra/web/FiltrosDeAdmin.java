package me.clama.infra.web;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.http.HttpStatus;

/** Leitura dos filtros das listas do admin: filtro malformado e 400 {@code dados_invalidos} apontando o campo. */
public final class FiltrosDeAdmin {

	private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

	private FiltrosDeAdmin() {}

	public static ErroDeNegocio invalido(String campo, String mensagem) {
		return new ErroDeNegocio(HttpStatus.BAD_REQUEST, "dados_invalidos", "Dados inválidos.", null,
				List.of(new ErroDeCampo(campo, mensagem)));
	}

	/** Dia {@code AAAA-MM-DD}; nulo ou em branco = sem filtro. */
	public static LocalDate dia(String campo, String valor) {
		if (valor == null || valor.isBlank()) return null;
		try {
			return LocalDate.parse(valor.strip());
		} catch (DateTimeParseException e) {
			throw invalido(campo, "Use o formato AAAA-MM-DD.");
		}
	}

	/** Meia-noite de Sao Paulo do dia (inclusivo). */
	public static Instant inicio(LocalDate dia) {
		return dia == null ? null : dia.atStartOfDay(SAO_PAULO).toInstant();
	}

	/** Meia-noite de Sao Paulo do dia seguinte (limite exclusivo do dia final). */
	public static Instant fimExclusivo(LocalDate dia) {
		return dia == null ? null : dia.plusDays(1).atStartOfDay(SAO_PAULO).toInstant();
	}

	public static void ordemDoPeriodo(LocalDate de, LocalDate ate) {
		if (de != null && ate != null && de.isAfter(ate)) throw invalido("de", "A data inicial vem depois da final.");
	}

	public static Long numero(String campo, String valor) {
		if (valor == null || valor.isBlank()) return null;
		try {
			return Long.valueOf(valor.strip());
		} catch (NumberFormatException e) {
			throw invalido(campo, "Informe um número.");
		}
	}

	public static Boolean booleano(String campo, String valor) {
		if (valor == null || valor.isBlank()) return null;
		return switch (valor.strip().toLowerCase(java.util.Locale.ROOT)) {
			case "true" -> true;
			case "false" -> false;
			default -> throw invalido(campo, "Use true ou false.");
		};
	}
}
