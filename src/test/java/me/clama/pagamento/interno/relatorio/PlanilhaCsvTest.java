package me.clama.pagamento.interno.relatorio;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import me.clama.pagamento.interno.relatorio.RelatorioDeRepasse.Interna;
import me.clama.pagamento.interno.relatorio.RelatorioDeRepasse.Linha;
import me.clama.pagamento.interno.relatorio.RelatorioDeRepasse.LinhaInterna;
import me.clama.pagamento.interno.relatorio.RelatorioDeRepasse.Previa;
import org.junit.jupiter.api.Test;

/**
 * O CSV tem de sair byte a byte como o do Django para o mesmo ledger. Os arquivos de {@code src/test/resources/relatorio}
 * foram gerados com o {@code csv.writer} do Python, com as mesmas chamadas do {@code _gravar_csv} de
 * {@code relatorio_repasse.py} (HEAD do clama-backend), {@code utf-8-sig} e {@code newline=""}.
 */
class PlanilhaCsvTest {

	static final LocalDate DE = LocalDate.of(2026, 10, 28);
	static final LocalDate ATE = LocalDate.of(2026, 11, 27);

	static byte[] django(String arquivo) throws IOException {
		try (var in = PlanilhaCsvTest.class.getResourceAsStream("/relatorio/" + arquivo)) {
			return in.readAllBytes();
		}
	}

	static Linha linha(String doador, String referencia, LocalDate data, long base, long repasse) {
		return new Linha(UUID.randomUUID(), doador, referencia, data, base, 20, repasse, "REGISTRADO");
	}

	@Test
	void duasDoacoesComPontoEVirgulaNoNomeDaInstituicaoEntreAspas() throws Exception {
		var previa = new Previa(1, "Lar; Esperança", DE, ATE, List.of(linha("Maria S.", "#a3f2…8b1c", DE, 5000, 1000),
				linha("José Á.", "#0001…ffff", LocalDate.of(2026, 11, 5), 1234, 246)));
		assertThat(PlanilhaCsv.daInstituicao(previa)).isEqualTo(django("duas-doacoes.csv"));
	}

	@Test
	void umaDoacaoNoSingularEAspasDuplicadasNoTitulo() throws Exception {
		var previa = new Previa(1, "Mar \"de\" Espanha", DE, ATE, List.of(linha("Ana", "#1111…2222", ATE, 3000, 600)));
		assertThat(PlanilhaCsv.daInstituicao(previa)).isEqualTo(django("uma-doacao.csv"));
	}

	@Test
	void periodoSemRepasseGeraOZerado() throws Exception {
		assertThat(PlanilhaCsv.daInstituicao(new Previa(1, "Pela Vida", DE, ATE, List.of()))).isEqualTo(django("zerado.csv"));
	}

	@Test
	void internaComIdDoGatewayEDivergencia() throws Exception {
		var linhas = List.of(
				new LinhaInterna(UUID.fromString("a3f2c0de-0000-4000-8000-000000008b1c"), "Maria Silva Santos", "maria@exemplo.com", "1325814423",
						"ENVIADA", DE, DE, 5000, 5000, 20, 1000, "TRANSFERIDO", LocalDate.of(2026, 11, 2), "PIX-E123; lote"),
				new LinhaInterna(UUID.fromString("00010000-0000-4000-8000-00000000ffff"), "(sem nome)", "", null, "PAGO",
						LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 5), 3000, 0, 20, 600, "REGISTRADO", null, ""));
		assertThat(PlanilhaCsv.interna(new Interna(1, "Lar; Esperança", DE, ATE, linhas))).isEqualTo(django("interna.csv"));
	}

	@Test
	void bomCrlfAspasMinimasECentavos() {
		var bytes = PlanilhaCsv.escrever(List.of(List.of("a", "b;c", "d\"e", "f\ng", "h i"), List.of(), List.of("")));
		assertThat(bytes).startsWith(0xEF, 0xBB, 0xBF);
		assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("﻿a;\"b;c\";\"d\"\"e\";\"f\ng\";h i\r\n\r\n\"\"\r\n");
		assertThat(PlanilhaCsv.reais(5000)).isEqualTo("50,00");
		assertThat(PlanilhaCsv.reais(246)).isEqualTo("2,46");
		assertThat(PlanilhaCsv.reais(5)).isEqualTo("0,05");
		assertThat(PlanilhaCsv.reais(123456789)).isEqualTo("1234567,89"); // sem milhar: a planilha pt-BR soma
	}

	@Test
	void csvDaInstituicaoNaoTemColunaDePii() {
		var texto = new String(PlanilhaCsv.daInstituicao(new Previa(1, "Lar", DE, ATE, List.of(linha("Maria S.", "#a3f2…8b1c", DE, 5000, 1000)))),
				StandardCharsets.UTF_8);
		assertThat(texto).doesNotContain("@").contains("Doador;Referência do pedido;Data da doação;Valor doado (R$);Percentual de repasse;Valor a receber (R$)");
	}

	@Test
	void campoQueComecaComFormulaGanhaApostrofo() {
		var csv = new String(PlanilhaCsv.escrever(List.of(List.of("=1+1", "+55", "-2", "@soma", "ok=", "a;=b"))), StandardCharsets.UTF_8);
		assertThat(csv).isEqualTo("\uFEFF'=1+1;'+55;'-2;'@soma;ok=;\"a;=b\"\r\n");
		assertThat(new String(PlanilhaCsv.escrever(List.of(List.of("=a;b"))), StandardCharsets.UTF_8)).isEqualTo("\uFEFF\"'=a;b\"\r\n");
	}
}
