package me.clama.pagamento.interno.relatorio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.YearMonth;
import me.clama.infra.web.ErroDeNegocio;
import org.junit.jupiter.api.Test;

/** O ciclo do dia 28 ao 27 (SCRIPT:50-56) e a leitura dos parametros da tela. */
class CicloTest {

	static void ciclo(String hoje, String de, String ate) {
		assertThat(Ciclo.fechadoEm(LocalDate.parse(hoje))).as("hoje = " + hoje).isEqualTo(new Ciclo(LocalDate.parse(de), LocalDate.parse(ate)));
	}

	@Test
	void noDia28OuDepoisFechaOCicloQueTerminaNo27DoMesCorrente() {
		ciclo("2026-11-28", "2026-10-28", "2026-11-27");
		ciclo("2026-11-30", "2026-10-28", "2026-11-27");
		ciclo("2026-01-31", "2025-12-28", "2026-01-27");
		ciclo("2026-12-31", "2026-11-28", "2026-12-27");
	}

	@Test
	void antesDoDia28ValeOCicloAnterior_o27EOUltimoDiaDoCicloAindaAberto() {
		ciclo("2026-11-27", "2026-09-28", "2026-10-27");
		ciclo("2026-11-01", "2026-09-28", "2026-10-27");
		ciclo("2026-11-15", "2026-09-28", "2026-10-27");
	}

	@Test
	void viradaDeAno() {
		ciclo("2027-01-01", "2026-11-28", "2026-12-27");
		ciclo("2027-01-27", "2026-11-28", "2026-12-27");
		ciclo("2027-01-28", "2026-12-28", "2027-01-27");
		ciclo("2026-01-15", "2025-11-28", "2025-12-27");
	}

	@Test
	void fevereiroComEBissexto() {
		ciclo("2026-02-27", "2025-12-28", "2026-01-27");
		ciclo("2026-02-28", "2026-01-28", "2026-02-27");
		ciclo("2026-03-01", "2026-01-28", "2026-02-27");
		ciclo("2026-03-28", "2026-02-28", "2026-03-27");
		ciclo("2028-02-29", "2028-01-28", "2028-02-27"); // bissexto: o 29 ainda e do ciclo que fecha em 27/02
		ciclo("2028-03-27", "2028-01-28", "2028-02-27");
		ciclo("2028-03-28", "2028-02-28", "2028-03-27");
	}

	@Test
	void mesInteiroComDezembroEFevereiro() {
		assertThat(Ciclo.doMes(YearMonth.of(2026, 12))).isEqualTo(new Ciclo(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31)));
		assertThat(Ciclo.doMes(YearMonth.of(2027, 2)).ate()).isEqualTo(LocalDate.of(2027, 2, 28));
		assertThat(Ciclo.doMes(YearMonth.of(2028, 2)).ate()).isEqualTo(LocalDate.of(2028, 2, 29));
	}

	static final LocalDate HOJE = LocalDate.of(2026, 11, 28);

	static void invalido(String mes, String de, String ate, String campo, String mensagem) {
		assertThatThrownBy(() -> Ciclo.resolver(mes, de, ate, HOJE)).isInstanceOfSatisfying(ErroDeNegocio.class, e -> {
			assertThat(e.status().value()).isEqualTo(400);
			assertThat(e.erros()).singleElement().satisfies(c -> {
				assertThat(c.campo()).isEqualTo(campo);
				assertThat(c.mensagem()).isEqualTo(mensagem);
			});
		});
	}

	@Test
	void resolverEscolheMesDatasOuOCicloPadrao() {
		assertThat(Ciclo.resolver(null, null, null, HOJE)).isEqualTo(new Ciclo(LocalDate.of(2026, 10, 28), LocalDate.of(2026, 11, 27)));
		assertThat(Ciclo.resolver("", " ", "", HOJE)).isEqualTo(Ciclo.fechadoEm(HOJE));
		assertThat(Ciclo.resolver("2026-02", null, null, HOJE)).isEqualTo(new Ciclo(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)));
		assertThat(Ciclo.resolver(null, "2026-03-01", "2026-03-01", HOJE)).isEqualTo(new Ciclo(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 1)));
	}

	@Test
	void combinacoesInvalidasSao400ComOCampo() {
		invalido("2026-02", "2026-02-01", null, "mes", "Use o mês ou as datas, não os dois.");
		invalido("2026-02", null, "2026-02-10", "mes", "Use o mês ou as datas, não os dois.");
		invalido(null, "2026-02-01", null, "ate", "Informe as duas datas.");
		invalido(null, null, "2026-02-01", "de", "Informe as duas datas.");
		invalido(null, "2026-03-02", "2026-03-01", "de", "A data inicial vem depois da final.");
		invalido("2026-13", null, null, "mes", "Use o formato AAAA-MM.");
		invalido("fevereiro", null, null, "mes", "Use o formato AAAA-MM.");
		invalido(null, "01/03/2026", "2026-03-02", "de", "Use o formato AAAA-MM-DD.");
	}
}
