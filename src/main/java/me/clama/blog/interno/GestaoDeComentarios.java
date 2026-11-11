package me.clama.blog.interno;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.clama.conta.ContaApi;
import me.clama.infra.auditoria.Acao;
import me.clama.infra.auditoria.Auditoria;
import me.clama.infra.web.ErroDeNegocio;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Comentarios fora do widget: a moderacao do admin e "Seus comentarios" da conta. Le por SQL com colunas escolhidas:
 * {@code ip_cifrado} nunca e lido (D11), quanto menos decifrado.
 */
@Service
public class GestaoDeComentarios {

	public static final String NAO_ENCONTRADO = "Esse comentário não foi encontrado.";

	/** Linha da moderacao: sem IP. */
	public record ParaAdmin(Long id, Instant criadoEm, String conteudo, boolean suspeito, String postSlug, String postTitulo,
			String postUrl, Long usuarioId, String usuarioEmail, String usuarioNome) {}

	/** Linha de "Seus comentarios". */
	public record DaConta(Long id, Instant criadoEm, String conteudo, String postSlug, String postTitulo, String postUrl) {}

	private record Linha(Long id, Instant criadoEm, String conteudo, boolean suspeito, String slug, String titulo, String url,
			Long usuarioId) {}

	private static final String DE = " from blog_comentario c join blog_post p on p.wp_id = c.post_id";
	private static final String CAMPOS = "select c.id, c.criado_em, c.conteudo, c.suspeito, p.slug, p.titulo, p.url, c.usuario_id";
	private static final String ORDEM = " order by c.criado_em desc, c.id desc limit :n offset :o";

	private final JdbcClient jdbc;
	private final TransactionTemplate transacao;
	private final Auditoria auditoria;
	private final ContaApi conta;

	GestaoDeComentarios(JdbcClient jdbc, TransactionTemplate transacao, Auditoria auditoria, ContaApi conta) {
		this.jdbc = jdbc;
		this.transacao = transacao;
		this.auditoria = auditoria;
		this.conta = conta;
	}

	public Page<ParaAdmin> listarParaAdmin(boolean suspeitos, String slug, Long usuarioId, Pageable pagina) {
		var onde = new StringBuilder(" where true");
		var params = new HashMap<String, Object>();
		if (suspeitos) onde.append(" and c.suspeito");
		if (slug != null && !slug.isBlank()) {
			onde.append(" and p.slug = :slug");
			params.put("slug", slug.strip());
		}
		if (usuarioId != null) {
			onde.append(" and c.usuario_id = :u");
			params.put("u", usuarioId);
		}
		var pagina0 = ler(onde.toString(), params, pagina);
		var nomes = conta.resumos(pagina0.getContent().stream().map(Linha::usuarioId).distinct().toList());
		return pagina0.map(l -> {
			var u = nomes.get(l.usuarioId());
			return new ParaAdmin(l.id(), l.criadoEm(), l.conteudo(), l.suspeito(), l.slug(), l.titulo(), l.url(), l.usuarioId(),
					u == null ? null : u.email(), u == null ? null : u.nomePublico());
		});
	}

	public Page<DaConta> listarDaConta(long usuarioId, Pageable pagina) {
		return ler(" where c.usuario_id = :u", Map.of("u", usuarioId), pagina)
				.map(l -> new DaConta(l.id(), l.criadoEm(), l.conteudo(), l.slug(), l.titulo(), l.url()));
	}

	private Page<Linha> ler(String onde, Map<String, Object> params, Pageable pagina) {
		var total = jdbc.sql("select count(*)" + DE + onde).params(params).query(Long.class).single();
		var linhas = jdbc.sql(CAMPOS + DE + onde + ORDEM).params(params)
				.param("n", pagina.getPageSize()).param("o", pagina.getOffset())
				.query((rs, n) -> new Linha(rs.getLong("id"), rs.getObject("criado_em", OffsetDateTime.class).toInstant(),
						rs.getString("conteudo"), rs.getBoolean("suspeito"), rs.getString("slug"), rs.getString("titulo"),
						rs.getString("url"), rs.getLong("usuario_id")))
				.list();
		return new PageImpl<>(linhas, pagina, total);
	}

	public void apagarComoAdmin(long autorId, long id) {
		transacao.executeWithoutResult(s -> {
			var apagado = jdbc.sql("delete from blog_comentario where id = :id returning usuario_id, post_id").param("id", id)
					.query((rs, n) -> List.of(rs.getLong(1), rs.getLong(2))).optional()
					.orElseThrow(GestaoDeComentarios::naoEncontrado);
			auditoria.registrar(autorId, "comentario", String.valueOf(id), Acao.EXCLUSAO,
					Map.of("usuarioId", apagado.get(0), "postWpId", apagado.get(1)));
		});
	}

	/** So o proprio: o de outra pessoa (ou inexistente) e 404, sem distinguir. */
	public void apagarDaConta(long usuarioId, long id) {
		var n = jdbc.sql("delete from blog_comentario where id = :id and usuario_id = :u").param("id", id).param("u", usuarioId)
				.update();
		if (n == 0) throw naoEncontrado();
	}

	private static ErroDeNegocio naoEncontrado() {
		return new ErroDeNegocio(HttpStatus.NOT_FOUND, "comentario_nao_encontrado", "Comentario nao encontrado", NAO_ENCONTRADO);
	}
}
