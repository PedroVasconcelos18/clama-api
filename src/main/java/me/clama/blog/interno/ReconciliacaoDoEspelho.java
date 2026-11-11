package me.clama.blog.interno;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import me.clama.blog.interno.ClienteWordPress.PostWp;
import me.clama.infra.fila.TarefaRecorrente;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code reconciliar-espelho} (D5/D6), a cada 15 min: le a lista publica inteira e so depois grava, numa transacao. Cria e
 * atualiza o que mudou; espelho publicado que sumiu da lista passa a nao publicado; nunca apaga. Falha em qualquer pagina,
 * mais de 50 paginas ou lista vazia: aborta sem gravar nada (Sentry; tenta de novo no proximo disparo).
 */
@Component
class ReconciliacaoDoEspelho extends TarefaRecorrente {

	private static final Logger LOG = LoggerFactory.getLogger(ReconciliacaoDoEspelho.class);
	static final int MAX_PAGINAS = 50;

	private final ClienteWordPress wordpress;
	private final Espelho espelho;
	private final PostRepository posts;
	private final TransactionTemplate transacao;
	private final Clock relogio;

	ReconciliacaoDoEspelho(ClienteWordPress wordpress, Espelho espelho, PostRepository posts, TransactionTemplate transacao, Clock relogio) {
		super("reconciliar-espelho", "0 */15 * * * *");
		this.wordpress = wordpress;
		this.espelho = espelho;
		this.posts = posts;
		this.transacao = transacao;
		this.relogio = relogio;
	}

	@Override
	protected void executar(boolean dryRun) {
		var inicioDaLeitura = relogio.instant();
		List<PostWp> lista;
		try {
			lista = lerTudo();
		} catch (ClienteWordPress.Indisponivel | IllegalStateException e) {
			LOG.warn("reconciliar-espelho abortada, nada foi gravado: {}", e.getMessage());
			Sentry.captureMessage("reconciliar-espelho abortada: " + e.getMessage(), SentryLevel.WARNING);
			return;
		}
		var existentes = new java.util.HashMap<Long, Post>();
		posts.findAll().forEach(p -> existentes.put(p.getWpId(), p));
		var mudados = lista.stream().filter(p -> mudou(existentes.get(p.id()), p)).toList();
		var vistos = new HashSet<Long>();
		lista.forEach(p -> vistos.add(p.id()));
		var sumidos = existentes.values().stream().filter(p -> p.isPublicado() && !vistos.contains(p.getWpId())).map(Post::getWpId).toList();
		if (dryRun) {
			LOG.info("reconciliar-espelho (dry-run): {} post(s) criados ou atualizados, {} deixariam de estar publicados", mudados.size(), sumidos.size());
			return;
		}
		transacao.executeWithoutResult(s -> {
			mudados.forEach(espelho::gravar);
			if (!sumidos.isEmpty()) posts.despublicar(sumidos, relogio.instant(), inicioDaLeitura);
		});
		LOG.info("reconciliar-espelho: {} post(s) criados ou atualizados, {} deixaram de estar publicados", mudados.size(), sumidos.size());
	}

	/** A lista publica inteira em memoria; qualquer pagina que falhe aborta. */
	private List<PostWp> lerTudo() {
		var primeira = wordpress.listar(1);
		if (primeira.totalPaginas() > MAX_PAGINAS) throw new IllegalStateException("a lista tem " + primeira.totalPaginas() + " paginas (maximo " + MAX_PAGINAS + ")");
		if (primeira.posts().isEmpty()) throw new IllegalStateException("a lista veio vazia");
		var todos = new ArrayList<>(primeira.posts());
		for (int n = 2; n <= primeira.totalPaginas(); n++) todos.addAll(wordpress.listar(n).posts());
		return todos;
	}

	private static boolean mudou(Post atual, PostWp novo) {
		return atual == null || !atual.getSlug().equals(novo.slug()) || !atual.getTitulo().equals(novo.titulo())
				|| !atual.getUrl().equals(novo.url()) || atual.isPublicado() != novo.publicado()
				|| !java.util.Objects.equals(atual.getPublicadoEm(), novo.publicadoEm());
	}
}
