package me.clama.blog.interno;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import me.clama.conta.ContaApi;
import me.clama.infra.alerta.AlertaAoAdmin;
import me.clama.infra.email.EnviadorDeEmail;
import me.clama.infra.email.ModelosDeEmail;
import me.clama.infra.fila.TarefaRecorrente;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@code alerta-comentarios} (D12), as 08:00: resumo dos comentarios das ultimas 24 h para o e-mail do admin, suspeitos
 * primeiro. <b>Sem o e-mail de quem comentou</b> (alerta sem PII): so o slug, o nome publico e o texto, que ja estao
 * publicos no blog. A janela e {@code (agora - 24 h, agora]}: o disparo de amanha comeca onde o de hoje acaba. Zero
 * comentario nao envia. A chave do dia (em Sao Paulo) deixa a Resend descartar um segundo envio no mesmo dia.
 */
@Component
class AlertaDeComentarios extends TarefaRecorrente {

	private static final Logger LOG = LoggerFactory.getLogger(AlertaDeComentarios.class);
	static final Duration JANELA = Duration.ofHours(24);
	static final int MAX_CARACTERES = 100;

	private record Linha(String slug, Long usuarioId, boolean suspeito, String conteudo) {}

	private final JdbcClient jdbc;
	private final ContaApi conta;
	private final ModelosDeEmail modelos;
	private final EnviadorDeEmail enviador;
	private final AlertaAoAdmin.Props props;
	private final Clock relogio;

	AlertaDeComentarios(JdbcClient jdbc, ContaApi conta, ModelosDeEmail modelos, EnviadorDeEmail enviador,
			AlertaAoAdmin.Props props, Clock relogio) {
		super("alerta-comentarios", "0 0 8 * * *");
		this.jdbc = jdbc;
		this.conta = conta;
		this.modelos = modelos;
		this.enviador = enviador;
		this.props = props;
		this.relogio = relogio;
	}

	@Override
	protected String executar(boolean dryRun) {
		var agora = relogio.instant();
		var desde = OffsetDateTime.ofInstant(agora.minus(JANELA), ZoneOffset.UTC);
		var ate = OffsetDateTime.ofInstant(agora, ZoneOffset.UTC);
		var linhas = jdbc.sql("""
				select p.slug, c.usuario_id, c.suspeito, c.conteudo
				from blog_comentario c join blog_post p on p.wp_id = c.post_id
				where c.criado_em > :desde and c.criado_em <= :ate
				order by c.suspeito desc, c.criado_em desc, c.id desc""")
				.param("desde", desde).param("ate", ate)
				.query((rs, n) -> new Linha(rs.getString("slug"), rs.getLong("usuario_id"), rs.getBoolean("suspeito"),
						rs.getString("conteudo")))
				.list();
		if (linhas.isEmpty()) {
			LOG.info("alerta-comentarios: nenhum comentario nas ultimas 24 h, nada enviado");
			return "nenhum comentário nas últimas 24 h, nada enviado";
		}
		var suspeitos = linhas.stream().filter(Linha::suspeito).count();
		if (dryRun) {
			LOG.info("alerta-comentarios (dry-run): {} comentario(s), {} suspeito(s), nada enviado", linhas.size(), suspeitos);
			return linhas.size() + " comentário(s), " + suspeitos + " suspeito(s), nada enviado (simulação)";
		}
		var nomes = conta.resumos(linhas.stream().map(Linha::usuarioId).distinct().toList());
		var texto = linhas.stream().map(l -> {
			var u = nomes.get(l.usuarioId());
			return "- /blog/" + l.slug() + " | " + (u == null ? "-" : u.nomePublico()) + (l.suspeito() ? " [SUSPEITO]" : "") + ": "
					+ cortar(l.conteudo());
		}).collect(Collectors.joining("\n"));
		var resumo = "Resumo de comentários do blog (" + linhas.size() + " novos, " + suspeitos + " suspeitos)";
		var link = props.frontendUrl().replaceAll("/+$", "") + "/admin/blog/comentarios";
		var email = modelos.montar(props.emailAdmin(), resumo, "alerta_comentarios",
				Map.of("resumo", resumo, "linhas", texto, "link", link));
		enviador.enviar(email, "alerta-comentarios/" + relogio.instant().atZone(FUSO).toLocalDate());
		LOG.info("alerta-comentarios: enviado ({} comentario(s), {} suspeito(s))", linhas.size(), suspeitos);
		return "resumo enviado: " + linhas.size() + " comentário(s), " + suspeitos + " suspeito(s)";
	}

	/** Uma linha so, no maximo 100 pontos de codigo (emoji nao parte ao meio); "…" so quando cortou. */
	static String cortar(String conteudo) {
		var uma = conteudo.replaceAll("\\s+", " ").strip();
		if (uma.codePointCount(0, uma.length()) <= MAX_CARACTERES) return uma;
		return uma.substring(0, uma.offsetByCodePoints(0, MAX_CARACTERES)) + "…";
	}
}
