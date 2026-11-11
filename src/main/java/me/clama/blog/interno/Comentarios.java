package me.clama.blog.interno;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import me.clama.conta.ContaApi;
import me.clama.infra.seguranca.LimiteDeTaxa;
import me.clama.infra.seguranca.LimiteDeTaxa.Cota;
import me.clama.infra.web.ErroDeNegocio;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Comentarios do blog (D7). Ordem do POST: {@link #liberar} (ban, depois limite) → validacao → espelho →
 * gravacao. O espelho (que pode ir ao WordPress) e resolvido ANTES e fora de qualquer transacao; so o
 * {@code save} abre a transacao, curta.
 */
@Service
public class Comentarios {

	public static final String CURTO = "Esse comentário precisa de um pouquinho mais de texto pra ser publicado.";
	public static final String LONGO = "Esse comentário passou de 2000 caracteres. Encurte um pouco e tente de novo.";
	public static final String SUSPENSA = "Sua conta foi suspensa do sistema de comentários. Entre em contato pelo nosso e-mail de suporte se quiser entender o motivo.";
	public static final String CALMA_COMENTAR = "Calma! Aguarde um momento antes de comentar de novo.";
	static final int MINIMO = 3;
	static final int MAXIMO = 2000;

	/** Comentario como o publico o ve: so o nome publico, nunca o id nem o e-mail do autor. */
	public record Publico(Long id, String nome, String conteudo, Instant criadoEm) {}

	/** Os mais novos e o total do post. */
	public record Lista(long total, List<Publico> itens) {}

	private final Espelho espelho;
	private final PostRepository posts;
	private final ComentarioRepository comentarios;
	private final Banimentos banimentos;
	private final ContaApi conta;
	private final LimiteDeTaxa limite;
	private final Clock relogio;

	Comentarios(Espelho espelho, PostRepository posts, ComentarioRepository comentarios, Banimentos banimentos,
			ContaApi conta, LimiteDeTaxa limite, Clock relogio) {
		this.espelho = espelho;
		this.posts = posts;
		this.comentarios = comentarios;
		this.banimentos = banimentos;
		this.conta = conta;
		this.limite = limite;
		this.relogio = relogio;
	}

	/** Leitura publica: so o banco, nunca o WordPress. Slug sem espelho (ou nao publicado) = lista vazia. */
	public Lista listar(String slug, String ip) {
		limite.exigir(new Cota("blog:leitura:ip:" + ip, 300, Duration.ofMinutes(1)));
		var post = posts.findFirstBySlugOrderByPublicadoDescPublicadoEmDescWpIdDesc(slug).filter(Post::isPublicado);
		if (post.isEmpty()) return new Lista(0, List.of());
		var id = post.get().getWpId();
		var novos = comentarios.findTop20ByPostIdOrderByCriadoEmDescIdDesc(id, ComentarioRepository.Linha.class);
		var nomes = conta.resumos(novos.stream().map(ComentarioRepository.Linha::getUsuarioId).distinct().toList());
		return new Lista(comentarios.countByPostId(id), novos.stream().map(c -> publico(c, nomes)).toList());
	}

	/** Primeiras barreiras do POST, antes de olhar o corpo: conta banida (409) e depois o limite de 5 por minuto (429). */
	public void liberar(long usuarioId) {
		exigirLivre(banimentos, usuarioId);
		try {
			limite.exigir(new Cota("blog:comentario:" + usuarioId, 5, Duration.ofMinutes(1)));
		} catch (ErroDeNegocio e) {
			throw new ErroDeNegocio(HttpStatus.TOO_MANY_REQUESTS, LimiteDeTaxa.CODIGO, e.getMessage(), CALMA_COMENTAR);
		}
	}

	/** Valida, resolve o espelho (404, 409, 503) e grava. {@code conteudo} nulo vale vazio. */
	public Publico criar(long usuarioId, String slug, String conteudo, String ip) {
		var texto = conteudo == null ? "" : conteudo.strip();
		var tamanho = texto.codePointCount(0, texto.length());
		if (tamanho > MAXIMO) throw new ErroDeNegocio(HttpStatus.BAD_REQUEST, "comentario_longo", "Comentario longo demais", LONGO);
		// NUL nao cabe em text do Postgres: seria um 500 na gravacao
		if (tamanho < MINIMO || texto.indexOf('\0') >= 0) throw new ErroDeNegocio(HttpStatus.BAD_REQUEST, "comentario_invalido", "Comentario curto demais", CURTO);
		var post = espelho.resolver(slug); // fora de transacao: pode ir ao WordPress
		var salvo = comentarios.save(new Comentario(post.getWpId(), usuarioId, texto, Moderacao.ehSuspeito(texto), ipLiteral(ip),
				relogio.instant().truncatedTo(ChronoUnit.MICROS)));
		return publico(linha(salvo), conta.resumos(List.of(usuarioId)));
	}

	/**
	 * O IP existe so para o Marco Civil, entao tem de ser um IP: o {@code remoteAddr} tambem pode vir lixo (com o
	 * {@code forward-headers-strategy: native} o Tomcat o troca pelo ultimo item do X-Forwarded-For, sem validar). Lixo = sem IP.
	 */
	static String ipLiteral(String ip) {
		try {
			return ip == null ? null : java.net.InetAddress.ofLiteral(ip.strip()).getHostAddress();
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static ComentarioRepository.Linha linha(Comentario c) {
		return new ComentarioRepository.Linha() {
			public Long getId() { return c.getId(); }
			public Long getUsuarioId() { return c.getUsuarioId(); }
			public String getConteudo() { return c.getConteudo(); }
			public Instant getCriadoEm() { return c.getCriadoEm(); }
		};
	}

	static void exigirLivre(Banimentos banimentos, long usuarioId) {
		if (banimentos.estaBanido(usuarioId)) {
			throw new ErroDeNegocio(HttpStatus.CONFLICT, "comentarios_suspensos", "Conta banida no blog", SUSPENSA);
		}
	}

	private static Publico publico(ComentarioRepository.Linha c, java.util.Map<Long, me.clama.conta.UsuarioResumo> nomes) {
		var autor = nomes.get(c.getUsuarioId());
		return new Publico(c.getId(), autor == null ? "" : autor.nomePublico(), c.getConteudo(), c.getCriadoEm());
	}
}
