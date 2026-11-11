package me.clama.blog.interno;

import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import me.clama.conta.ContaApi;
import me.clama.conta.UsuarioResumo;
import me.clama.infra.auditoria.Acao;
import me.clama.infra.auditoria.Auditoria;
import me.clama.infra.web.ErroDeNegocio;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ban ativo do blog: uma linha por conta em {@code blog_banimento} (D9). Banir e {@code INSERT ... ON CONFLICT DO NOTHING}
 * (a PK resolve o ban duplo); revogar apaga a linha, e o historico fica na auditoria (com o motivo).
 */
@Component
public class Banimentos {

	public static final String MOTIVO_CURTO = "Informe um motivo para o banimento (≥3 caracteres).";
	public static final String USUARIO_INEXISTENTE = "Esse usuário não foi encontrado.";
	public static final String NAO_PODE_BANIR_ADMIN = "Admins não podem ser banidos. Para tirar o acesso, mude o papel da conta antes.";
	public static final String SEM_BAN = "Essa conta não está banida.";
	static final int MOTIVO_MAXIMO = 500;

	/** Ban como o admin o ve. {@code banidoPorEmail} e nulo quando quem baniu foi excluido. */
	public record Item(Long usuarioId, String usuarioEmail, String usuarioNome, String motivo, Instant banidoEm,
			String banidoPorEmail) {}

	/** {@code criado}: 201 (novo) ou 200 (ja banido, sem mudar o motivo). */
	public record Resultado(Item ban, boolean criado) {}

	private final JdbcClient jdbc;
	private final TransactionTemplate transacao;
	private final Auditoria auditoria;
	private final ContaApi conta;
	private final Clock relogio;

	Banimentos(JdbcClient jdbc, TransactionTemplate transacao, Auditoria auditoria, ContaApi conta, Clock relogio) {
		this.jdbc = jdbc;
		this.transacao = transacao;
		this.auditoria = auditoria;
		this.conta = conta;
		this.relogio = relogio;
	}

	/** A conta esta banida (qualquer papel). */
	public boolean estaBanido(long usuarioId) {
		return jdbc.sql("select exists (select 1 from blog_banimento where usuario_id = :id)")
				.param("id", usuarioId).query(Boolean.class).single();
	}

	public Resultado banir(long autorId, long usuarioId, String motivo) {
		var texto = motivo == null ? "" : motivo.strip();
		if (texto.codePointCount(0, texto.length()) < 3) {
			throw new ErroDeNegocio(HttpStatus.BAD_REQUEST, "motivo_invalido", "Motivo curto demais", MOTIVO_CURTO);
		}
		if (texto.codePointCount(0, texto.length()) > MOTIVO_MAXIMO) {
			throw new ErroDeNegocio(HttpStatus.BAD_REQUEST, "motivo_invalido", "Motivo longo demais",
					"Esse motivo passou de 500 caracteres. Encurte um pouco e tente de novo.");
		}
		var alvo = conta.resumos(List.of(usuarioId)).get(usuarioId);
		if (alvo == null) {
			throw new ErroDeNegocio(HttpStatus.NOT_FOUND, "usuario_nao_encontrado", "Usuario nao encontrado", USUARIO_INEXISTENTE);
		}
		if (alvo.admin()) {
			throw new ErroDeNegocio(HttpStatus.CONFLICT, "nao_pode_banir_admin", "Admin nao pode ser banido", NAO_PODE_BANIR_ADMIN);
		}
		boolean criado;
		try {
			criado = Boolean.TRUE.equals(transacao.execute(s -> {
				var inseriu = jdbc.sql("""
						insert into blog_banimento (usuario_id, motivo, banido_por_id, banido_em)
						values (:u, :motivo, :autor, :em) on conflict (usuario_id) do nothing""")
						.param("u", usuarioId).param("motivo", texto).param("autor", autorId)
						.param("em", OffsetDateTime.ofInstant(relogio.instant(), ZoneOffset.UTC)).update() == 1;
				if (inseriu) auditoria.registrar(autorId, "banimento", String.valueOf(usuarioId), Acao.ADICAO, Map.of("motivo", texto));
				return inseriu;
			}));
		} catch (DataIntegrityViolationException e) { // a conta sumiu entre a checagem e o insert (FK)
			throw usuarioSumiu();
		}
		var ban = listar(usuarioId, Pageable.ofSize(1)).getContent();
		if (ban.isEmpty()) throw usuarioSumiu(); // revogado ou conta apagada entre o insert e a leitura
		return new Resultado(ban.get(0), criado);
	}

	private static ErroDeNegocio usuarioSumiu() {
		return new ErroDeNegocio(HttpStatus.NOT_FOUND, "usuario_nao_encontrado", "Usuario nao encontrado", USUARIO_INEXISTENTE);
	}

	public void revogar(long autorId, long usuarioId) {
		transacao.executeWithoutResult(s -> {
			var motivo = jdbc.sql("delete from blog_banimento where usuario_id = :u returning motivo")
					.param("u", usuarioId).query(String.class).optional();
			if (motivo.isEmpty()) throw new ErroDeNegocio(HttpStatus.NOT_FOUND, "banimento_nao_encontrado", "Sem ban", SEM_BAN);
			auditoria.registrar(autorId, "banimento", String.valueOf(usuarioId), Acao.EXCLUSAO, Map.of("motivo", motivo.get()));
		});
	}

	/** Mais novos primeiro; {@code usuarioId} nulo = todos. */
	public Page<Item> listar(Long usuarioId, Pageable pagina) {
		var onde = usuarioId == null ? "" : " where usuario_id = :u";
		var total = bind(jdbc.sql("select count(*) from blog_banimento" + onde), usuarioId).query(Long.class).single();
		var linhas = bind(jdbc.sql("select usuario_id, motivo, banido_por_id, banido_em from blog_banimento" + onde
				+ " order by banido_em desc, usuario_id desc limit :n offset :o"), usuarioId)
				.param("n", pagina.getPageSize()).param("o", pagina.getOffset())
				.query((rs, n) -> linha(rs)).list();
		var ids = new HashSet<Long>();
		linhas.forEach(l -> {
			ids.add(l.usuarioId());
			if (l.porId() != null) ids.add(l.porId());
		});
		var resumos = conta.resumos(ids);
		var itens = linhas.stream().map(l -> {
			var u = resumos.get(l.usuarioId());
			var por = l.porId() == null ? null : resumos.get(l.porId());
			return new Item(l.usuarioId(), u == null ? null : u.email(), u == null ? null : u.nomePublico(), l.motivo(),
					l.em(), por == null ? null : por.email());
		}).toList();
		return new PageImpl<>(itens, pagina, total);
	}

	private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Long usuarioId) {
		return usuarioId == null ? spec : spec.param("u", usuarioId);
	}

	private record Linha(Long usuarioId, String motivo, Long porId, Instant em) {}

	private static Linha linha(ResultSet rs) throws java.sql.SQLException {
		var por = rs.getLong("banido_por_id");
		return new Linha(rs.getLong("usuario_id"), rs.getString("motivo"), rs.wasNull() ? null : por,
				rs.getObject("banido_em", OffsetDateTime.class).toInstant());
	}
}
