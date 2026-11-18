package me.clama.pagamento.interno.relatorio;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import me.clama.infra.web.FiltrosDeAdmin;

/**
 * Periodo do relatorio de repasse: dias de Sao Paulo, as duas pontas inclusivas. O ciclo do Pedro vai do dia 28 ao dia 27
 * (SCRIPT:50-56); o mes e o intervalo livre vem da tela.
 */
public record Ciclo(LocalDate de, LocalDate ate) {

	/** O ultimo ciclo fechado em {@code hoje}: do dia 28 ao 27 seguinte (28/10 a 27/11 vale de 28/11 em diante). */
	public static Ciclo fechadoEm(LocalDate hoje) {
		var fim = hoje.getDayOfMonth() >= 28 ? hoje.withDayOfMonth(27) : hoje.minusMonths(1).withDayOfMonth(27);
		return new Ciclo(fim.minusMonths(1).withDayOfMonth(28), fim);
	}

	/** O ciclo aberto em {@code hoje}: termina no proximo dia 27 (inclusive) e comeca no 28 anterior; o dia 28 abre um novo. */
	public static Ciclo emCurso(LocalDate hoje) {
		var fim = hoje.getDayOfMonth() <= 27 ? hoje.withDayOfMonth(27) : hoje.plusMonths(1).withDayOfMonth(27);
		return new Ciclo(fim.minusMonths(1).withDayOfMonth(28), fim);
	}

	/** Do 1o ao ultimo dia do mes (dezembro vira janeiro sem estourar; fevereiro bissexto incluso). */
	public static Ciclo doMes(YearMonth mes) {
		return new Ciclo(mes.atDay(1), mes.atEndOfMonth());
	}

	/**
	 * Os parametros da tela: nada informado = {@link #fechadoEm}; {@code mes} (AAAA-MM) ou {@code de} e {@code ate}
	 * (AAAA-MM-DD, os dois). Qualquer outra combinacao e 400 {@code dados_invalidos} com o campo.
	 */
	public static Ciclo resolver(String mes, String de, String ate, LocalDate hoje) {
		var temMes = mes != null && !mes.isBlank();
		var temDe = de != null && !de.isBlank();
		var temAte = ate != null && !ate.isBlank();
		if (temMes && (temDe || temAte)) throw FiltrosDeAdmin.invalido("mes", "Use o mês ou as datas, não os dois.");
		if (temMes) {
			try {
				return doMes(YearMonth.parse(mes.strip()));
			} catch (DateTimeParseException e) {
				throw FiltrosDeAdmin.invalido("mes", "Use o formato AAAA-MM.");
			}
		}
		if (temDe != temAte) throw FiltrosDeAdmin.invalido(temDe ? "ate" : "de", "Informe as duas datas.");
		if (!temDe) return fechadoEm(hoje);
		var inicio = FiltrosDeAdmin.dia("de", de);
		var fim = FiltrosDeAdmin.dia("ate", ate);
		FiltrosDeAdmin.ordemDoPeriodo(inicio, fim);
		return new Ciclo(inicio, fim);
	}
}
