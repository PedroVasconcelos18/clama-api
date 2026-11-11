package me.clama.infra.fila;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Historico de execucoes das tarefas recorrentes (D9): grava no fim de cada disparo e guarda 90 dias por tarefa. */
@Component
public class Execucoes {

	static final Duration RETENCAO = Duration.ofDays(90);

	/** {@code erro}: so a classe da excecao. {@code resumo}: uma linha, sem PII. */
	public record Execucao(long id, String tarefa, Instant inicio, Instant fim, boolean simulacao, boolean manual, Long autorId,
			boolean ok, String resumo, String erro) {}

	private final JdbcClient jdbc;

	Execucoes(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Grava e, na sequencia, apaga as da mesma tarefa com mais de 90 dias (o indice {@code (tarefa, inicio)} torna isso barato). */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void gravar(String tarefa, Instant inicio, Instant fim, boolean simulacao, boolean manual, Long autorId, boolean ok, String resumo,
			String erro) {
		jdbc.sql("""
				insert into tarefa_execucao (tarefa, inicio, fim, simulacao, manual, autor_id, ok, resumo, erro)
				values (:tarefa, :inicio, :fim, :simulacao, :manual, :autor, :ok, :resumo, :erro)""")
				.param("tarefa", tarefa).param("inicio", utc(inicio)).param("fim", utc(fim)).param("simulacao", simulacao)
				.param("manual", manual).param("autor", autorId).param("ok", ok).param("resumo", cortar(resumo, 500))
				.param("erro", cortar(erro, 200)).update();
		jdbc.sql("delete from tarefa_execucao where tarefa = :tarefa and inicio < :corte").param("tarefa", tarefa)
				.param("corte", utc(fim.minus(RETENCAO))).update();
	}

	public Page<Execucao> listar(String tarefa, Pageable pagina) {
		var itens = jdbc.sql(SELECT + " where tarefa = :tarefa order by inicio desc, id desc limit :n offset :o").param("tarefa", tarefa)
				.param("n", pagina.getPageSize()).param("o", pagina.getOffset()).query(Execucoes::linha).list();
		var total = jdbc.sql("select count(*) from tarefa_execucao where tarefa = :tarefa").param("tarefa", tarefa).query(Long.class)
				.single();
		return new PageImpl<>(itens, pagina, total);
	}

	/** A mais recente de cada tarefa. */
	List<Execucao> ultimas() {
		return jdbc.sql(SELECT.replace("select ", "select distinct on (tarefa) ") + " order by tarefa, inicio desc, id desc")
				.query(Execucoes::linha).list();
	}

	/**
	 * A mais recente de cada tarefa, sem as simulacoes manuais do "Rodar agora": e ela que diz se a tarefa "falhou". Uma tarefa
	 * configurada com dry-run roda "simulada" no agendado, e a falha dela conta.
	 */
	List<Execucao> ultimasQueContam() {
		return jdbc.sql(SELECT.replace("select ", "select distinct on (tarefa) ") + " where not (manual and simulacao) order by tarefa, inicio desc, id desc")
				.query(Execucoes::linha).list();
	}

	private static final String SELECT = "select id, tarefa, inicio, fim, simulacao, manual, autor_id, ok, resumo, erro from tarefa_execucao";

	private static Execucao linha(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
		var autor = rs.getLong("autor_id");
		return new Execucao(rs.getLong("id"), rs.getString("tarefa"), rs.getObject("inicio", OffsetDateTime.class).toInstant(),
				rs.getObject("fim", OffsetDateTime.class).toInstant(), rs.getBoolean("simulacao"), rs.getBoolean("manual"),
				rs.wasNull() ? null : autor, rs.getBoolean("ok"), rs.getString("resumo"), rs.getString("erro"));
	}

	private static OffsetDateTime utc(Instant i) {
		return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
	}

	private static String cortar(String s, int max) {
		return s == null || s.length() <= max ? s : s.substring(0, max);
	}
}
