package me.clama.pagamento.interno.relatorio;

import static me.clama.pagamento.interno.relatorio.RelatorioDeRepasse.Interna;
import static me.clama.pagamento.interno.relatorio.RelatorioDeRepasse.Previa;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV do relatorio de repasse, byte a byte o do Django ({@code relatorio_repasse.py}): UTF-8 com BOM, {@code ;}, CRLF, aspas
 * so quando o campo tem {@code ;}, {@code "} ou quebra de linha (o {@code QUOTE_MINIMAL} do {@code csv} do Python). So a stdlib.
 * O da instituicao nao tem coluna de PII: nem e-mail, nem CPF, nem o texto do pedido.
 */
public final class PlanilhaCsv {

	private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
	private static final List<String> CABECALHO = List.of("Doador", "Referência do pedido", "Data da doação", "Valor doado (R$)",
			"Percentual de repasse", "Valor a receber (R$)");
	private static final List<String> CABECALHO_INTERNO = List.of("Doador (nome completo)", "E-mail", "ID do pedido",
			"ID do pagamento no gateway", "Status do pedido", "Data do pedido", "Data da confirmação", "Valor no ledger (R$)",
			"Valor atual do pedido (R$)", "Percentual", "Repasse (R$)", "Status do repasse", "Transferido em", "Comprovante", "Divergência");

	private PlanilhaCsv() {}

	public static byte[] daInstituicao(Previa r) {
		var l = new ArrayList<List<String>>();
		l.add(List.of("Relatório de repasse — " + r.instituicao()));
		l.add(List.of("Período: " + periodo(r.de(), r.ate())));
		l.add(List.of());
		l.add(CABECALHO);
		for (var x : r.linhas()) {
			l.add(List.of(x.doador(), x.referencia(), x.data().format(DATA), reais(x.valorDoadoCentavos()), x.percentual() + "%",
					reais(x.valorRepasseCentavos())));
		}
		l.add(List.of());
		l.add(List.of(total(r.quantidade()), "", "", reais(r.totalDoadoCentavos()), "", reais(r.totalRepasseCentavos())));
		return escrever(l);
	}

	public static byte[] interna(Interna r) {
		var l = new ArrayList<List<String>>();
		l.add(List.of("AUDITORIA INTERNA de repasse — " + r.instituicao()));
		l.add(List.of("Período: " + periodo(r.de(), r.ate())));
		l.add(List.of("USO INTERNO — contém dado pessoal. Não entregar à instituição."));
		l.add(List.of());
		l.add(CABECALHO_INTERNO);
		for (var x : r.linhas()) {
			l.add(List.of(x.nome(), x.email(), x.pedidoId().toString(), x.mpPagamentoId() == null ? "(sem id no gateway)" : x.mpPagamentoId(),
					x.statusDoPedido(), x.pedidoCriadoEm() == null ? "" : x.pedidoCriadoEm().format(DATA), x.data().format(DATA),
					reais(x.valorNoLedgerCentavos()), reais(x.valorAtualDoPedidoCentavos()), x.percentual() + "%", reais(x.valorRepasseCentavos()),
					x.statusDoRepasse(), x.transferidoEm() == null ? "" : x.transferidoEm().format(DATA), x.comprovante(), x.divergencia()));
		}
		l.add(List.of());
		var total = new ArrayList<>(java.util.Collections.nCopies(CABECALHO_INTERNO.size(), ""));
		total.set(0, total(r.quantidade()));
		total.set(7, reais(r.totalDoadoCentavos()));
		total.set(10, reais(r.totalRepasseCentavos()));
		l.add(total);
		return escrever(l);
	}

	/** BOM, linhas separadas por CRLF (e terminadas por ele), aspas minimas. Linha vazia = so o CRLF. */
	public static byte[] escrever(List<List<String>> linhas) {
		var s = new StringBuilder("﻿");
		for (var linha : linhas) {
			if (linha.size() == 1 && linha.get(0).isEmpty()) {
				s.append("\"\""); // o csv do Python nao deixa a linha de um campo vazio sumir
			} else {
				for (int i = 0; i < linha.size(); i++) {
					if (i > 0) s.append(';');
					s.append(campo(linha.get(i)));
				}
			}
			s.append("\r\n");
		}
		return s.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static String campo(String valor) {
		if (valor.indexOf(';') < 0 && valor.indexOf('"') < 0 && valor.indexOf('\r') < 0 && valor.indexOf('\n') < 0) return valor;
		return "\"" + valor.replace("\"", "\"\"") + "\"";
	}

	/** {@code 5000} vira {@code 50,00}: sem "R$" e sem milhar, para a planilha pt-BR somar a coluna. */
	static String reais(long centavos) {
		return Math.floorDiv(centavos, 100) + "," + String.format("%02d", Math.floorMod(centavos, 100));
	}

	private static String total(int quantidade) {
		return "TOTAL (" + quantidade + (quantidade == 1 ? " doação)" : " doações)");
	}

	private static String periodo(LocalDate de, LocalDate ate) {
		return de.format(DATA) + " a " + ate.format(DATA);
	}
}
