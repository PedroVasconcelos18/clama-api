package me.clama.infra.web;

import java.time.Clock;
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

	/** Periodo em dias de SP, {@code [inicio, fimExclusivo)}. */
	public record Periodo(Instant inicio, Instant fimExclusivo) {}

	/**
	 * {@code de}/{@code ate} das metricas: o padrao e do dia 1 do mes de {@code ate} (do mes corrente se nao vier) ate
	 * {@code ate} (hoje em SP se nao vier). Malformado ou fora de ordem e 400.
	 */
	public static Periodo periodoDoMes(String de, String ate, Clock relogio) {
		var fim = dia("ate", ate);
		if (fim == null) fim = relogio.instant().atZone(SAO_PAULO).toLocalDate();
		var inicio = dia("de", de);
		if (inicio == null) inicio = fim.withDayOfMonth(1);
		ordemDoPeriodo(inicio, fim);
		return new Periodo(inicio(inicio), fimExclusivo(fim));
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
