package me.clama.oracao.interno;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import me.clama.infra.auditoria.Acao;
import me.clama.infra.auditoria.Auditoria;
import me.clama.infra.web.ErroDeNegocio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Documentos de contexto (D14). A Files API da Anthropic e a fonte da verdade; o banco guarda so os metadados
 * ({@code documento_contexto}), e nada fica em disco alem do arquivo temporario do upload, apagado no fim. So PDF
 * ({@code %PDF-}) e TXT (UTF-8 valido, sem NUL), detectados pelo conteudo e nunca pelo Content-Type ou pela extensao;
 * ate 100 MB, contados enquanto o corpo chega. As chamadas a Anthropic ficam fora das transacoes (o upload leva
 * segundos); cada escrita local e auditada.
 */
@Component
public class AdminDeDocumentos {

	private static final Logger LOG = LoggerFactory.getLogger(AdminDeDocumentos.class);
	static final String ENTIDADE = "documento_contexto";
	public static final long MAXIMO = 100L * 1024 * 1024;

	public record Documento(long id, String nome, @Nullable String mime, @Nullable Long tamanhoBytes, @Nullable String fileId,
			boolean ativo, Instant criadoEm, Instant atualizadoEm) {}

	/** Uploads ao mesmo tempo: cada um segura um temporario de ate 100 MB e uma conexao longa com a Anthropic. */
	private final Semaphore vagas = new Semaphore(2);
	private final JdbcClient jdbc;
	private final Auditoria auditoria;
	private final TransactionTemplate transacao;
	private final Clock relogio;
	private final ClienteClaude claude;

	AdminDeDocumentos(JdbcClient jdbc, Auditoria auditoria, TransactionTemplate transacao, Clock relogio, ClienteClaude claude) {
		this.jdbc = jdbc;
		this.auditoria = auditoria;
		this.transacao = transacao;
		this.relogio = relogio;
		this.claude = claude;
	}

	public List<Documento> listar() {
		return jdbc.sql("select * from documento_contexto order by id").query((rs, i) -> documento(rs)).list();
	}

	public Documento documento(long id) {
		return jdbc.sql("select * from documento_contexto where id = :id").param("id", id).query((rs, i) -> documento(rs)).optional()
				.orElseThrow(() -> new ErroDeNegocio(HttpStatus.NOT_FOUND, "documento_nao_encontrado", "Documento não encontrado.",
						"Esse documento não foi encontrado."));
	}

	/** O que a geracao anexa: so os ativos, em ordem estavel (a ordem faz parte do prefixo do cache). */
	public List<String> fileIdsAtivos() {
		return jdbc.sql("select file_id from documento_contexto where ativo order by id").query(String.class).list();
	}

	public Documento criar(Long autorId, String nome) {
		return transacao.execute(s -> {
			var agora = Timestamp.from(relogio.instant());
			var id = jdbc.sql("insert into documento_contexto (nome, criado_em, atualizado_em) values (:n, :em, :em) returning id")
					.param("n", nome.strip()).param("em", agora).query(Long.class).single();
			auditoria.registrar(autorId, ENTIDADE, String.valueOf(id), Acao.ADICAO, Map.of());
			return documento(id);
		});
	}

	/**
	 * Recebe o corpo cru: recusa na hora pelo Content-Length (-1 = desconhecido) e quando ja ha 2 uploads (503), grava num
	 * temporario contando os bytes (acima de 100 MB, 400 sem ler o resto), valida o formato, sobe para a Files API e guarda o
	 * {@code file_id} com compare-and-set sobre o antigo: quem perde a corrida apaga o arquivo novo la e recebe 409. Se ja
	 * havia outro arquivo, apaga o antigo la depois (falha so avisa).
	 */
	public Documento receberArquivo(Long autorId, long id, InputStream corpo, long contentLength) {
		var antigo = documento(id);
		if (contentLength > MAXIMO) throw grandeDemais();
		if (!vagas.tryAcquire()) {
			throw new ErroDeNegocio(HttpStatus.SERVICE_UNAVAILABLE, "documento_upload_ocupado", "Uploads em andamento.",
					"Já há envios de documentos em andamento. Tente de novo em instantes.");
		}
		Path temp = null;
		try {
			final var arquivo = temp = Files.createTempFile("documento-", ".tmp");
			long tamanho = copiar(corpo, temp);
			var mime = detectar(temp, tamanho);
			var nomeNaApi = "documento-" + id + (mime.equals("application/pdf") ? ".pdf" : ".txt");
			String fileId = na(() -> claude.enviarArquivo(arquivo, nomeNaApi, mime));
			var atualizado = transacao.execute(s -> gravar(autorId, id, antigo, fileId, mime, tamanho));
			if (atualizado == null) {
				apagarSemFalhar(fileId);
				if (existe(id)) {
					throw new ErroDeNegocio(HttpStatus.CONFLICT, "documento_conflito", "Envio concorrente.",
							"Outro envio para esse documento aconteceu ao mesmo tempo. Envie de novo.");
				}
				throw naoEncontrado();
			}
			if (antigo.fileId() != null) apagarSemFalhar(antigo.fileId());
			return atualizado;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} finally {
			vagas.release();
			if (temp != null) {
				try {
					Files.deleteIfExists(temp);
				} catch (IOException e) {
					LOG.warn("Nao apagou o temporario do upload de documento (event=documento_temp)");
				}
			}
		}
	}

	/**
	 * Renomear e/ou ativar/desativar. Ativar confere na Files API que o arquivo ainda existe (409 se sumiu); o resto e
	 * local, numa transacao, cada campo que muda auditado.
	 */
	public Documento alterar(Long autorId, long id, @Nullable String nome, @Nullable Boolean ativo) {
		var d = documento(id);
		if (Boolean.TRUE.equals(ativo) && !d.ativo()) {
			if (d.fileId() == null) {
				throw new ErroDeNegocio(HttpStatus.CONFLICT, "documento_sem_arquivo", "Documento sem arquivo.",
						"Envie o arquivo do documento antes de ativá-lo.");
			}
			if (!na(() -> claude.arquivoExiste(d.fileId()))) {
				throw new ErroDeNegocio(HttpStatus.CONFLICT, "documento_arquivo_sumiu", "Arquivo não existe mais na Anthropic.",
						"O arquivo desse documento não existe mais na Anthropic. Envie-o de novo.");
			}
		}
		return transacao.execute(s -> {
			var agora = Timestamp.from(relogio.instant());
			var novoNome = nome == null ? null : nome.strip();
			if (novoNome != null && !novoNome.equals(d.nome())
					&& jdbc.sql("update documento_contexto set nome = :n, atualizado_em = :em where id = :id").param("n", novoNome)
							.param("em", agora).param("id", id).update() > 0) {
				auditoria.registrar(autorId, ENTIDADE, String.valueOf(id), Acao.MUDANCA, Map.of("nome", Map.of("antes", d.nome(), "depois", novoNome)));
			}
			if (ativo != null && jdbc.sql("update documento_contexto set ativo = :a, atualizado_em = :em where id = :id and ativo <> :a")
					.param("a", ativo).param("em", agora).param("id", id).update() > 0) {
				auditoria.registrar(autorId, ENTIDADE, String.valueOf(id), Acao.MUDANCA, Map.of("ativo", Map.of("antes", !ativo, "depois", ativo)));
			}
			return documento(id);
		});
	}

	/** Apaga na Files API primeiro (404 conta como apagado); qualquer outra falha deixa a linha e vira 502. */
	public void apagar(Long autorId, long id) {
		var d = documento(id);
		if (d.fileId() != null) na(() -> {
			claude.apagarArquivo(d.fileId());
			return null;
		});
		transacao.executeWithoutResult(s -> {
			if (jdbc.sql("delete from documento_contexto where id = :id").param("id", id).update() > 0) {
				auditoria.registrar(autorId, ENTIDADE, String.valueOf(id), Acao.EXCLUSAO, Map.of());
			}
		});
	}

	private @Nullable Documento gravar(Long autorId, long id, Documento antigo, String fileId, String mime, long tamanho) {
		var linhas = jdbc.sql("""
				update documento_contexto set file_id = :f, mime = :m, tamanho_bytes = :t, atualizado_em = :em where id = :id and file_id is not distinct from cast(:antigo as text)""")
				.param("antigo", antigo.fileId()).param("f", fileId).param("m", mime).param("t", tamanho).param("em", Timestamp.from(relogio.instant())).param("id", id)
				.update();
		if (linhas == 0) return null;
		var diff = new LinkedHashMap<String, Object>();
		diff.put("fileId", Map.of("antes", String.valueOf(antigo.fileId() == null ? "" : antigo.fileId()), "depois", fileId));
		diff.put("tamanhoBytes", Map.of("antes", antigo.tamanhoBytes() == null ? 0L : antigo.tamanhoBytes(), "depois", tamanho));
		auditoria.registrar(autorId, ENTIDADE, String.valueOf(id), Acao.MUDANCA, diff);
		return documento(id);
	}

	private boolean existe(long id) {
		return jdbc.sql("select count(*) from documento_contexto where id = :id").param("id", id).query(Long.class).single() > 0;
	}

	private static ErroDeNegocio grandeDemais() {
		return new ErroDeNegocio(HttpStatus.BAD_REQUEST, "documento_grande_demais", "Documento grande demais.",
				"O documento passa de 100 MB. Envie uma versão menor.");
	}

	/** Copia contando; passou de 100 MB, 400 na hora. */
	private static long copiar(InputStream corpo, Path destino) throws IOException {
		long total = 0;
		var buf = new byte[64 * 1024];
		try (var saida = Files.newOutputStream(destino)) {
			int n;
			while ((n = corpo.read(buf)) > 0) {
				total += n;
				if (total > MAXIMO) {
					throw grandeDemais();
				}
				saida.write(buf, 0, n);
			}
		}
		return total;
	}

	/** {@code %PDF-} no inicio = PDF; senao tem de ser UTF-8 valido sem NUL (DOCX, PNG e afins caem aqui e falham). */
	private static String detectar(Path arquivo, long tamanho) throws IOException {
		if (tamanho > 0) {
			try (var in = Files.newInputStream(arquivo)) {
				if (new String(in.readNBytes(5), StandardCharsets.ISO_8859_1).equals("%PDF-")) return "application/pdf";
			}
			try (var leitor = Files.newBufferedReader(arquivo)) { // UTF-8 estrito: byte invalido lanca
				int c;
				boolean ok = true;
				while ((c = leitor.read()) >= 0) {
					if (c == 0) {
						ok = false;
						break;
					}
				}
				if (ok) return "text/plain";
			} catch (CharacterCodingException e) {
				// cai no erro abaixo
			}
		}
		throw new ErroDeNegocio(HttpStatus.BAD_REQUEST, "documento_invalido", "Formato de documento inválido.",
				"Só aceitamos PDF e TXT (texto UTF-8). Verifique o arquivo enviado.");
	}

	private void apagarSemFalhar(String fileId) {
		try {
			claude.apagarArquivo(fileId);
		} catch (ClienteClaude.Falha e) {
			LOG.warn("Arquivo orfao na Files API (event=documento_orfao, file_id={})", fileId);
		}
	}

	/** Falha da Files API vira 502 com mensagem nossa; a da Anthropic nunca sai. */
	private static <T> T na(java.util.function.Supplier<T> chamada) {
		try {
			return chamada.get();
		} catch (ClienteClaude.Falha e) {
			throw new ErroDeNegocio(HttpStatus.BAD_GATEWAY, "anthropic_falhou", "Falha na Files API da Anthropic.",
					"Não deu para falar com a Anthropic agora. Tente de novo em instantes.");
		}
	}

	private static ErroDeNegocio naoEncontrado() {
		return new ErroDeNegocio(HttpStatus.NOT_FOUND, "documento_nao_encontrado", "Documento não encontrado.",
				"Esse documento não foi encontrado.");
	}

	private static Documento documento(ResultSet rs) throws SQLException {
		return new Documento(rs.getLong("id"), rs.getString("nome"), rs.getString("mime"), rs.getObject("tamanho_bytes", Long.class),
				rs.getString("file_id"), rs.getBoolean("ativo"), rs.getTimestamp("criado_em").toInstant(),
				rs.getTimestamp("atualizado_em").toInstant());
	}
}
