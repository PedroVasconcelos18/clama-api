package me.clama.pagamento.interno.relatorio;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import me.clama.catalogo.CatalogoApi;
import me.clama.infra.alerta.AlertaAoAdmin;
import me.clama.infra.email.EnviadorDeEmail;
import me.clama.infra.email.ModelosDeEmail;
import me.clama.infra.fila.TarefaRecorrente;
import me.clama.infra.formato.Dinheiro;
import me.clama.infra.web.FiltrosDeAdmin;
import me.clama.pagamento.interno.relatorio.RelatorioDeRepasse.Previa;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@code relatorio-repasse-mensal} (D6), dia 28 as 10:00 de Sao Paulo: no lugar do launchd do Mac. Ciclo = {@link Ciclo#fechadoEm}.
 * Alvo = o banco, nunca uma lista: instituicoes ativas mais qualquer uma (inclusive inativa) com repasse no ciclo. Cada uma
 * isolada; o resumo vai para {@code relatorio_repasse} (upsert) e o e-mail ao admin sai <b>sem anexo e sem dado de doador</b>,
 * mesmo com tudo zerado (e o sinal de que rodou). Se alguma faltou, o e-mail sai primeiro e depois a tarefa lanca
 * {@link FalhaParcial}. A auditoria do ledger e isolada: erro tecnico vira "nao rodou", nunca "critico".
 * {@code dry-run}: so calcula e devolve o resumo.
 */
@Component
public class RelatorioMensal extends TarefaRecorrente {

	private static final Logger LOG = LoggerFactory.getLogger(RelatorioMensal.class);
	private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
	static final int MAX_ACHADOS_NO_EMAIL = 20;

	private final CatalogoApi catalogo;
	private final RelatorioDeRepasse relatorio;
	private final RelatoriosMensais resumos;
	private final AuditoriaDoLedger auditoria;
	private final JdbcClient jdbc;
	private final ModelosDeEmail modelos;
	private final EnviadorDeEmail enviador;
	private final AlertaAoAdmin.Props props;
	private final Clock relogio;

	RelatorioMensal(CatalogoApi catalogo, RelatorioDeRepasse relatorio, RelatoriosMensais resumos, AuditoriaDoLedger auditoria,
			JdbcClient jdbc, ModelosDeEmail modelos, EnviadorDeEmail enviador, AlertaAoAdmin.Props props, Clock relogio) {
		super("relatorio-repasse-mensal", "0 0 10 28 * *");
		this.catalogo = catalogo;
		this.relatorio = relatorio;
		this.resumos = resumos;
		this.auditoria = auditoria;
		this.jdbc = jdbc;
		this.modelos = modelos;
		this.enviador = enviador;
		this.props = props;
		this.relogio = relogio;
	}

	@Override
	protected String executar(boolean dryRun) {
		var agora = relogio.instant();
		var ciclo = Ciclo.fechadoEm(agora.atZone(FUSO).toLocalDate());
		var periodo = DIA.format(ciclo.de()) + " a " + DIA.format(ciclo.ate());

		var alvos = alvos(ciclo);
		var feitas = new ArrayList<Previa>();
		var faltou = new ArrayList<String>();
		for (var alvo : alvos.entrySet()) {
			try {
				var previa = relatorio.previa(alvo.getKey(), ciclo);
				if (!dryRun) resumos.salvar(previa, agora);
				feitas.add(previa);
			} catch (RuntimeException e) {
				LOG.error("relatorio-repasse-mensal: instituicao {} falhou", alvo.getKey(), e);
				faltou.add(alvo.getValue());
			}
		}

		AuditoriaDoLedger.Resultado ledger = null;
		try {
			ledger = auditoria.auditar();
		} catch (RuntimeException e) {
			LOG.error("relatorio-repasse-mensal: a auditoria do ledger nao rodou", e);
		}
		var estornos = estornos(ciclo);

		var totalRepasse = feitas.stream().mapToLong(Previa::totalRepasseCentavos).sum();
		var atencao = !faltou.isEmpty() || ledger == null || ledger.criticos() > 0;
		var situacaoDoLedger = ledger == null ? "auditoria não rodou" : ledger.criticos() > 0 ? ledger.criticos() + " crítico(s)" : "ledger ok";
		var resumo = "ciclo " + periodo + ": " + feitas.size() + " de " + alvos.size() + " instituições, " + Dinheiro.reais(totalRepasse)
				+ " a repassar; " + situacaoDoLedger + (dryRun ? " (simulação: nada gravado nem enviado)" : "");

		var falha = falha(feitas.size(), alvos.size(), faltou, ledger == null);
		if (dryRun) {
			LOG.info("relatorio-repasse-mensal (dry-run): {}", resumo);
			if (falha != null) throw new FalhaParcial(resumo + "; " + falha);
			return resumo;
		}

		var link = props.frontendUrl().replaceAll("/+$", "") + "/admin/relatorios";
		var email = modelos.montar(props.emailAdmin(), "Repasse do ciclo " + periodo + (atencao ? ": atenção" : ": relatórios prontos"),
				"relatorio_repasse_mensal", Map.of(
						"ciclo", periodo,
						"instituicoes", feitas.isEmpty() ? "Nenhuma instituição." : feitas.stream().map(RelatorioMensal::linha).collect(Collectors.joining("\n")),
						"faltou", faltou.isEmpty() ? "Nenhuma." : String.join(", ", faltou),
						"ledger", textoDoLedger(ledger),
						"estornos", estornos,
						"link", link));
		enviador.enviar(email, "relatorio-repasse/" + ciclo.ate());
		LOG.info("relatorio-repasse-mensal: e-mail enviado ({} instituicao(oes), {} faltou)", feitas.size(), faltou.size());

		if (falha != null) throw new FalhaParcial(feitas.size() + " de " + alvos.size() + " instituições, " + falha);
		return resumo;
	}

	/** Uma so falha para a execucao ({@code ok=false}), sem PII: o que faltou e/ou a auditoria que nao rodou; nulo se deu tudo certo. */
	private static String falha(int feitas, int alvos, List<String> faltou, boolean auditoriaNaoRodou) {
		var partes = new ArrayList<String>();
		if (!faltou.isEmpty()) partes.add("faltou: " + String.join(", ", faltou));
		if (auditoriaNaoRodou) partes.add("auditoria do ledger não rodou");
		return partes.isEmpty() ? null : String.join("; ", partes);
	}

	/** id -> nome: as ativas mais quem tem repasse no ciclo (inclusive inativa), em ordem de nome. */
	private Map<Long, String> alvos(Ciclo ciclo) {
		var nomes = new LinkedHashMap<Long, String>();
		catalogo.instituicoesAtivas().forEach(i -> nomes.put(i.id(), i.nome()));
		var extras = relatorio.instituicoesComRepasse(ciclo).stream().filter(id -> !nomes.containsKey(id)).toList();
		if (!extras.isEmpty()) {
			catalogo.instituicoes(extras).values().forEach(i -> nomes.put(i.id(), i.nome()));
			extras.forEach(id -> nomes.putIfAbsent(id, "instituição #" + id));
		}
		var ordenado = new LinkedHashMap<Long, String>();
		nomes.entrySet().stream().sorted(Map.Entry.comparingByValue(Comparator.comparing(String::toLowerCase))).forEach(e -> ordenado.put(e.getKey(), e.getValue()));
		return ordenado;
	}

	private static String linha(Previa p) {
		var doacoes = p.quantidade() + (p.quantidade() == 1 ? " doação" : " doações");
		var pendente = p.totalPendenteCentavos() == p.totalRepasseCentavos() ? "" : " (" + Dinheiro.reais(p.totalPendenteCentavos()) + " ainda pendentes)";
		return "- " + p.instituicao() + ": " + doacoes + ", " + Dinheiro.reais(p.totalRepasseCentavos()) + " a repassar" + pendente;
	}

	private static String textoDoLedger(AuditoriaDoLedger.Resultado ledger) {
		if (ledger == null) return "A auditoria não rodou (erro técnico; veja Tarefas).";
		if (ledger.achados().isEmpty()) return "Ledger consistente.";
		var texto = new StringBuilder();
		texto.append(ledger.criticos() > 0
				? "Ledger com " + ledger.criticos() + " achado(s) CRÍTICO(S), " + Dinheiro.reais(ledger.emJogoCentavos()) + " em jogo."
				: "Ledger sem críticos, " + ledger.achados().size() + " ponto(s) de atenção.");
		ledger.achados().stream().limit(MAX_ACHADOS_NO_EMAIL).forEach(a -> texto.append("\n- ").append(a.gravidade()).append(" ").append(a.tipo().codigo())
				.append(", ").append(a.instituicao()).append(", pedido ").append(RelatorioDeRepasse.referencia(a.pedidoId())).append(": ")
				.append(a.detalhe()).append(" (").append(Dinheiro.reais(a.valorEmJogoCentavos())).append(")"));
		if (ledger.achados().size() > MAX_ACHADOS_NO_EMAIL) texto.append("\n- … e mais ").append(ledger.achados().size() - MAX_ACHADOS_NO_EMAIL).append(" na tela do ledger.");
		return texto.toString();
	}

	/**
	 * Estornos do ciclo que pedem acerto: o pagamento foi estornado depois de o repasse ser transferido
	 * ({@code repasse_transferido}) ou parcialmente ({@code estorno_parcial}). So referencia do pedido e valor do repasse.
	 */
	private String estornos(Ciclo ciclo) {
		try {
			record Estorno(String motivo, UUID pedido, long instituicao, long valor) {}
			var lista = jdbc.sql("""
					select distinct e.motivo, e.pedido_id, r.instituicao_id, r.valor_centavos
					from pagamento_evento e join repasse r on r.pedido_id = e.pedido_id
					where e.motivo in ('repasse_transferido', 'estorno_parcial') and e.atualizado_em >= :de and e.atualizado_em < :ate
					order by 2, 1""")
					.param("de", Timestamp.from(FiltrosDeAdmin.inicio(ciclo.de()))).param("ate", Timestamp.from(FiltrosDeAdmin.fimExclusivo(ciclo.ate())))
					.query((rs, i) -> new Estorno(rs.getString("motivo"), rs.getObject("pedido_id", UUID.class), rs.getLong("instituicao_id"),
							rs.getLong("valor_centavos")))
					.list();
			if (lista.isEmpty()) return "Nenhum.";
			var nomes = catalogo.instituicoes(lista.stream().map(Estorno::instituicao).distinct().toList());
			return lista.stream().map(e -> {
				var i = nomes.get(e.instituicao());
				return "- " + (e.motivo().equals("estorno_parcial") ? "estorno parcial" : "estorno depois de transferido") + ", "
						+ (i == null ? "instituição #" + e.instituicao() : i.nome()) + ", pedido " + RelatorioDeRepasse.referencia(e.pedido())
						+ ", repasse de " + Dinheiro.reais(e.valor());
			}).collect(Collectors.joining("\n"));
		} catch (RuntimeException e) {
			LOG.error("relatorio-repasse-mensal: nao listou os estornos", e);
			return "Não consegui listar os estornos (veja Tarefas).";
		}
	}
}
