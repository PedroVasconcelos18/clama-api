package me.clama.blog.interno;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import me.clama.infra.seguranca.LimiteDeTaxa;
import me.clama.infra.seguranca.LimiteDeTaxa.Cota;
import me.clama.infra.web.ErroDeNegocio;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Curtidas (D8): o clique alterna. PK (post, usuario); {@code ON CONFLICT DO NOTHING} faz o clique duplo nunca dar 500. */
@Service
public class Curtidas {

	static final String CALMA_REAGIR = "Calma! Aguarde um momento antes de reagir de novo.";

	/** {@code curtido}: a conta curte o post agora; {@code total}: curtidas do post. */
	public record Resultado(boolean curtido, long total) {}

	private final Espelho espelho;
	private final Banimentos banimentos;
	private final LimiteDeTaxa limite;
	private final JdbcClient jdbc;
	private final TransactionTemplate transacao;
	private final Clock relogio;

	Curtidas(Espelho espelho, Banimentos banimentos, LimiteDeTaxa limite, JdbcClient jdbc, TransactionTemplate transacao,
			Clock relogio) {
		this.espelho = espelho;
		this.banimentos = banimentos;
		this.limite = limite;
		this.jdbc = jdbc;
		this.transacao = transacao;
		this.relogio = relogio;
	}

	/** Ban (409), limite de 30 por minuto (429), espelho (fora de transacao) e so entao o toggle. */
	public Resultado alternar(long usuarioId, String slug) {
		Comentarios.exigirLivre(banimentos, usuarioId);
		try {
			limite.exigir(new Cota("blog:curtida:" + usuarioId, 30, Duration.ofMinutes(1)));
		} catch (ErroDeNegocio e) {
			throw new ErroDeNegocio(HttpStatus.TOO_MANY_REQUESTS, LimiteDeTaxa.CODIGO, e.getMessage(), CALMA_REAGIR);
		}
		var post = espelho.resolver(slug).getWpId(); // fora de transacao: pode ir ao WordPress
		var curtido = transacao.execute(s -> {
			var apagou = jdbc.sql("delete from blog_curtida where post_id = :p and usuario_id = :u")
					.param("p", post).param("u", usuarioId).update();
			if (apagou > 0) return false;
			jdbc.sql("insert into blog_curtida (post_id, usuario_id, criado_em) values (:p, :u, :agora) on conflict do nothing")
					.param("p", post).param("u", usuarioId)
					.param("agora", java.time.OffsetDateTime.ofInstant(relogio.instant().truncatedTo(ChronoUnit.MICROS), java.time.ZoneOffset.UTC)).update();
			return true; // 0 linhas = o outro clique ganhou: a conta curte o post do mesmo jeito
		});
		var total = jdbc.sql("select count(*) from blog_curtida where post_id = :p").param("p", post).query(Long.class).single();
		return new Resultado(Boolean.TRUE.equals(curtido), total);
	}
}
