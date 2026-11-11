package me.clama.pagamento.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import me.clama.infra.web.FiltrosDeAdmin;
import me.clama.pagamento.interno.AdminDePagamento;
import me.clama.pagamento.interno.relatorio.AuditoriaDoLedger;
import me.clama.pagamento.interno.relatorio.Ciclo;
import me.clama.pagamento.interno.relatorio.PlanilhaCsv;
import me.clama.pagamento.interno.relatorio.RelatorioDeRepasse;
import me.clama.pagamento.interno.relatorio.RelatoriosMensais;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Relatorio de repasse, auditoria do ledger e quitacao em lote (ADMIN, pela cadeia padrao). Periodo: {@code mes},
 * {@code de}+{@code ate} ou, sem nada, o ultimo ciclo fechado. O CSV da instituicao e a previa so tem o nome abreviado; o CSV
 * interno (nome inteiro, e-mail, id do MP) grava auditoria a cada download.
 */
@RestController
@RequestMapping("/api/admin/repasses")
class RelatoriosDeRepasseController {

	private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
	private static final MediaType CSV = MediaType.parseMediaType("text/csv;charset=UTF-8");

	private final RelatorioDeRepasse relatorio;
	private final AuditoriaDoLedger auditoriaDoLedger;
	private final AdminDePagamento admin;
	private final RelatoriosMensais mensais;
	private final Clock relogio;

	RelatoriosDeRepasseController(RelatorioDeRepasse relatorio, AuditoriaDoLedger auditoriaDoLedger, AdminDePagamento admin,
			RelatoriosMensais mensais, Clock relogio) {
		this.mensais = mensais;
		this.relatorio = relatorio;
		this.auditoriaDoLedger = auditoriaDoLedger;
		this.admin = admin;
		this.relogio = relogio;
	}

	record LinhaDoRelatorio(String doador, String referencia, LocalDate data, long valorDoadoCentavos, int percentual,
			long valorRepasseCentavos, String status) {}

	record PreviaDoRelatorio(String instituicao, LocalDate de, LocalDate ate, List<LinhaDoRelatorio> linhas, int quantidade,
			long totalDoadoCentavos, long totalRepasseCentavos, int pendentes, long totalPendenteCentavos) {}

	@GetMapping("/relatorio")
	PreviaDoRelatorio previaDoRelatorio(@RequestParam(required = false) @Size(max = 20) String instituicaoId,
			@RequestParam(required = false) @Size(max = 7) String mes, @RequestParam(required = false) @Size(max = 10) String de,
			@RequestParam(required = false) @Size(max = 10) String ate) {
		var p = relatorio.previa(instituicao(instituicaoId), periodo(mes, de, ate));
		return new PreviaDoRelatorio(p.instituicao(), p.de(), p.ate(),
				p.linhas().stream().map(l -> new LinhaDoRelatorio(l.doador(), l.referencia(), l.data(), l.valorDoadoCentavos(), l.percentual(),
						l.valorRepasseCentavos(), l.status())).toList(),
				p.quantidade(), p.totalDoadoCentavos(), p.totalRepasseCentavos(), p.pendentes(), p.totalPendenteCentavos());
	}

	@GetMapping("/relatorio.csv")
	ResponseEntity<byte[]> csvDaInstituicao(@RequestParam(required = false) @Size(max = 20) String instituicaoId,
			@RequestParam(required = false) @Size(max = 7) String mes, @RequestParam(required = false) @Size(max = 10) String de,
			@RequestParam(required = false) @Size(max = 10) String ate) {
		var p = relatorio.previa(instituicao(instituicaoId), periodo(mes, de, ate));
		return csv(PlanilhaCsv.daInstituicao(p), "relatorio-repasse", p.instituicao(), p.de(), p.ate());
	}

	/** Contem PII: grava auditoria a cada download. */
	@GetMapping("/relatorio-interno.csv")
	ResponseEntity<byte[]> csvInterno(@RequestParam(required = false) @Size(max = 20) String instituicaoId,
			@RequestParam(required = false) @Size(max = 7) String mes, @RequestParam(required = false) @Size(max = 10) String de,
			@RequestParam(required = false) @Size(max = 10) String ate, Authentication auth) {
		var r = relatorio.internaAuditada(Long.valueOf(auth.getName()), instituicao(instituicaoId), periodo(mes, de, ate));
		return csv(PlanilhaCsv.interna(r), "auditoria-INTERNA-repasse", r.instituicao(), r.de(), r.ate());
	}

	/** O que a previa mostrou: {@code quantidade} e {@code totalCentavos} conferem os pendentes; diferente = 409 {@code recorte_mudou}, sem mudar nada. */
	record QuitacaoEmLote(@NotNull Long instituicaoId, @NotBlank @Size(max = 10) String de, @NotBlank @Size(max = 10) String ate,
			@NotBlank @Size(max = 120) String comprovante, @NotNull @Min(1) Integer quantidade, @NotNull Long totalCentavos) {}

	record Quitados(int quantidade, long totalCentavos) {}

	@PostMapping("/transferencias")
	Quitados quitarEmLote(@Valid @RequestBody QuitacaoEmLote corpo, Authentication auth) {
		var de = FiltrosDeAdmin.dia("de", corpo.de());
		var ate = FiltrosDeAdmin.dia("ate", corpo.ate());
		FiltrosDeAdmin.ordemDoPeriodo(de, ate);
		relatorio.nomeDaInstituicao(corpo.instituicaoId());
		var q = admin.transferirLote(Long.valueOf(auth.getName()), corpo.instituicaoId(), de, ate, corpo.comprovante(), corpo.quantidade(), corpo.totalCentavos());
		return new Quitados(q.quantidade(), q.totalCentavos());
	}

	record AchadoDoLedger(String gravidade, String tipo, String instituicao, java.util.UUID pedidoId, String detalhe, long valorEmJogoCentavos) {}

	record AuditoriaDoLedgerAdmin(List<AchadoDoLedger> achados, int criticos, long emJogoCentavos) {}

	/** Vazio = ledger consistente. */
	@GetMapping("/auditoria")
	AuditoriaDoLedgerAdmin auditarLedger() {
		var r = auditoriaDoLedger.auditar();
		return new AuditoriaDoLedgerAdmin(r.achados().stream().map(a -> new AchadoDoLedger(a.gravidade().name(), a.tipo().codigo(), a.instituicao(),
				a.pedidoId(), a.detalhe(), a.valorEmJogoCentavos())).toList(), r.criticos(), r.emJogoCentavos());
	}

	/** Os resumos que a tarefa do dia 28 guardou, mais novos primeiro; {@code totalAgoraCentavos} mostra se mudou depois (estorno). */
	@GetMapping("/relatorios-mensais")
	PagedModel<RelatoriosMensais.Resumo> relatoriosMensais(@ParameterObject @PageableDefault(size = 20) Pageable pagina) {
		return new PagedModel<>(mensais.listar(PageRequest.of(pagina.getPageNumber(), pagina.getPageSize(), Sort.unsorted())));
	}

	private long instituicao(String valor) {
		var id = FiltrosDeAdmin.numero("instituicaoId", valor);
		if (id == null) throw FiltrosDeAdmin.invalido("instituicaoId", "Informe a instituição.");
		return id;
	}

	private Ciclo periodo(String mes, String de, String ate) {
		return Ciclo.resolver(mes, de, ate, LocalDate.now(relogio.withZone(SAO_PAULO)));
	}

	private static ResponseEntity<byte[]> csv(byte[] corpo, String prefixo, String instituicao, LocalDate de, LocalDate ate) {
		var arquivo = prefixo + "-" + slug(instituicao) + "-" + de + "_a_" + ate + ".csv";
		return ResponseEntity.ok().contentType(CSV)
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(arquivo).build().toString()).body(corpo);
	}

	/** Sem acento, minusculo, so {@code a-z0-9} ligados por "-" (o {@code _slug} do Django, mais estrito: o nome vai num cabecalho HTTP). */
	static String slug(String texto) {
		var s = Normalizer.normalize(texto, Normalizer.Form.NFKD).replaceAll("[^\\p{ASCII}]", "").toLowerCase(java.util.Locale.ROOT)
				.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
		return s.isEmpty() ? "instituicao" : s;
	}
}
