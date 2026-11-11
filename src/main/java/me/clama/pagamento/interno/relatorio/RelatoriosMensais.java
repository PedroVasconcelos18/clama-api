package me.clama.pagamento.interno.relatorio;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import me.clama.catalogo.CatalogoApi;
import me.clama.infra.web.FiltrosDeAdmin;
import me.clama.pagamento.interno.relatorio.RelatorioDeRepasse.Previa;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Resumos mensais guardados ({@code relatorio_repasse}, D5): instituicao, ciclo e totais, nunca o CSV (tem nome de doador).
 * Um por (instituicao, ciclo): gerar de novo atualiza a mesma linha.
 */
@Component
public class RelatoriosMensais {

	public record Resumo(long id, long instituicaoId, String instituicao, LocalDate de, LocalDate ate, Instant geradoEm, int quantidade,
			long totalDoadoCentavos, long totalRepasseCentavos, long pendenteCentavos, long totalAgoraCentavos) {}

	private final JdbcClient jdbc;
	private final CatalogoApi catalogo;

	RelatoriosMensais(JdbcClient jdbc, CatalogoApi catalogo) {
		this.jdbc = jdbc;
		this.catalogo = catalogo;
	}

	public void salvar(Previa p, Instant geradoEm) {
		jdbc.sql("""
				insert into relatorio_repasse (instituicao_id, de, ate, gerado_em, quantidade, total_doado_centavos, total_repasse_centavos, pendente_centavos)
				values (:inst, :de, :ate, :gerado, :qtd, :doado, :repasse, :pendente)
				on conflict (instituicao_id, de, ate) do update set gerado_em = excluded.gerado_em, quantidade = excluded.quantidade,
				total_doado_centavos = excluded.total_doado_centavos, total_repasse_centavos = excluded.total_repasse_centavos,
				pendente_centavos = excluded.pendente_centavos""")
				.param("inst", p.instituicaoId()).param("de", p.de()).param("ate", p.ate()).param("gerado", Timestamp.from(geradoEm))
				.param("qtd", p.quantidade()).param("doado", p.totalDoadoCentavos()).param("repasse", p.totalRepasseCentavos())
				.param("pendente", p.totalPendenteCentavos()).update();
	}

	/** Mais novos primeiro. {@code totalAgoraCentavos}: o repasse do mesmo recorte hoje (um estorno depois da geracao muda). */
	public Page<Resumo> listar(Pageable pagina) {
		var linhas = lerPagina(pagina);
		var total = jdbc.sql("select count(*) from relatorio_repasse").query(Long.class).single();
		return new PageImpl<>(linhas, pagina, total);
	}

	private List<Resumo> lerPagina(Pageable pagina) {
		record Linha(long id, long inst, LocalDate de, LocalDate ate, Instant gerado, int qtd, long doado, long repasse, long pendente) {}
		var linhas = jdbc.sql("""
				select id, instituicao_id, de, ate, gerado_em, quantidade, total_doado_centavos, total_repasse_centavos, pendente_centavos
				from relatorio_repasse order by gerado_em desc, id desc limit :n offset :o""")
				.param("n", pagina.getPageSize()).param("o", pagina.getOffset())
				.query((rs, i) -> new Linha(rs.getLong("id"), rs.getLong("instituicao_id"), rs.getObject("de", LocalDate.class),
						rs.getObject("ate", LocalDate.class), rs.getTimestamp("gerado_em").toInstant(), rs.getInt("quantidade"),
						rs.getLong("total_doado_centavos"), rs.getLong("total_repasse_centavos"), rs.getLong("pendente_centavos")))
				.list();
		var nomes = catalogo.instituicoes(linhas.stream().map(Linha::inst).distinct().toList());
		return linhas.stream().map(l -> {
			var agora = jdbc.sql("""
					select coalesce(sum(valor_centavos), 0) from repasse
					where instituicao_id = :inst and status <> 'CANCELADO' and criado_em >= :de and criado_em < :ate""")
					.param("inst", l.inst()).param("de", Timestamp.from(FiltrosDeAdmin.inicio(l.de())))
					.param("ate", Timestamp.from(FiltrosDeAdmin.fimExclusivo(l.ate()))).query(Long.class).single();
			var i = nomes.get(l.inst());
			return new Resumo(l.id(), l.inst(), i == null ? "(instituição #" + l.inst() + ")" : i.nome(), l.de(), l.ate(), l.gerado(), l.qtd(),
					l.doado(), l.repasse(), l.pendente(), agora);
		}).toList();
	}
}
