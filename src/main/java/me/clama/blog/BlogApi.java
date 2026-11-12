package me.clama.blog;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import me.clama.blog.interno.ComentarioRepository;
import me.clama.blog.interno.PostRepository;
import me.clama.infra.auditoria.Auditoria;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Interface do modulo para os outros (hoje so a LGPD): o que a conta tem no blog. */
@Service
public class BlogApi {

	/** O que uma exclusao apagaria (ou apagou) do blog da conta. {@code banido}: ha ban ativo. */
	public record Contagem(long comentarios, long curtidas, boolean banido) {}

	/** Linhas apagadas por {@link #apagarDoUsuario}. */
	public record Apagados(long comentarios, long curtidas) {}

	private final JdbcClient jdbc;
	private final ComentarioRepository comentarios;
	private final PostRepository posts;
	private final Auditoria auditoria;

	BlogApi(JdbcClient jdbc, ComentarioRepository comentarios, PostRepository posts, Auditoria auditoria) {
		this.jdbc = jdbc;
		this.comentarios = comentarios;
		this.posts = posts;
		this.auditoria = auditoria;
	}

	@Transactional(readOnly = true)
	public Contagem contagem(Long usuarioId) {
		return jdbc.sql("""
				select (select count(*) from blog_comentario where usuario_id = :u) c,
				  (select count(*) from blog_curtida where usuario_id = :u) l,
				  exists (select 1 from blog_banimento where usuario_id = :u) b""")
				.param("u", usuarioId).query((rs, n) -> new Contagem(rs.getLong("c"), rs.getLong("l"), rs.getBoolean("b"))).single();
	}

	/**
	 * Tudo da conta no blog (LGPD): comentarios com o IP decifrado, curtidas, o ban ativo e o historico de bans da
	 * auditoria (acao, motivo, quando). Datas em {@code Instant}. Nunca logar o resultado.
	 */
	@Transactional(readOnly = true)
	public Map<String, Object> exportar(Long usuarioId) {
		var lista = comentarios.findByUsuarioIdOrderByCriadoEmAscIdAsc(usuarioId);
		var slugs = posts.findAllById(lista.stream().map(c -> c.getPostId()).distinct().toList()).stream()
				.collect(Collectors.toMap(p -> p.getWpId(), p -> p.getSlug()));
		var coms = new ArrayList<Map<String, Object>>();
		for (var c : lista) {
			var m = new LinkedHashMap<String, Object>();
			m.put("id", c.getId());
			m.put("postSlug", slugs.get(c.getPostId()));
			m.put("conteudo", c.getConteudo());
			m.put("ip", c.getIp());
			m.put("suspeito", c.isSuspeito());
			m.put("criadoEm", c.getCriadoEm());
			coms.add(m);
		}
		var curtidas = jdbc.sql("""
				select p.slug, c.criado_em from blog_curtida c join blog_post p on p.wp_id = c.post_id
				where c.usuario_id = :u order by c.criado_em, p.slug""").param("u", usuarioId).query((rs, n) -> {
					var m = new LinkedHashMap<String, Object>();
					m.put("postSlug", rs.getString("slug"));
					m.put("criadoEm", rs.getObject("criado_em", OffsetDateTime.class).toInstant());
					return (Map<String, Object>) m;
				}).list();
		var ban = jdbc.sql("select motivo, banido_em from blog_banimento where usuario_id = :u").param("u", usuarioId)
				.query((rs, n) -> {
					var m = new LinkedHashMap<String, Object>();
					m.put("motivo", rs.getString("motivo"));
					m.put("banidoEm", rs.getObject("banido_em", OffsetDateTime.class).toInstant());
					return (Map<String, Object>) m;
				}).optional().orElse(null);
		var historico = auditoria.daEntidade("banimento", String.valueOf(usuarioId)).stream().map(r -> {
			var m = new LinkedHashMap<String, Object>();
			m.put("acao", r.acao());
			m.put("motivo", r.diff().get("motivo"));
			m.put("em", r.ocorridoEm());
			return (Map<String, Object>) m;
		}).toList();
		var saida = new LinkedHashMap<String, Object>();
		saida.put("comentarios", coms);
		saida.put("curtidas", curtidas);
		saida.put("banimento", ban);
		saida.put("historicoDeBanimentos", historico);
		return saida;
	}

	/** Apaga comentarios (com os IPs) e curtidas da conta; a conta, os pedidos e o ban ficam (INV:164). */
	@Transactional(propagation = Propagation.MANDATORY)
	public Apagados apagarDoUsuario(Long usuarioId) {
		var c = jdbc.sql("delete from blog_comentario where usuario_id = :u").param("u", usuarioId).update();
		var l = jdbc.sql("delete from blog_curtida where usuario_id = :u").param("u", usuarioId).update();
		return new Apagados(c, l);
	}
}
