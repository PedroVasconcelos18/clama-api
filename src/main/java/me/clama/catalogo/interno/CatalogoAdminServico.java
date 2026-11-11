package me.clama.catalogo.interno;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import me.clama.catalogo.Complexidade;
import me.clama.infra.auditoria.Acao;
import me.clama.infra.auditoria.Auditoria;
import me.clama.infra.web.ErroDeCampo;
import me.clama.infra.web.ErroDeNegocio;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Escritas do admin sobre planos e instituicoes. Cada uma e uma transacao curta (trava a linha, muda, audita o diff).
 * Nome duplicado entre ativos: o indice unico parcial decide (a checagem previa perderia a corrida); a violacao
 * estoura no flush DENTRO da transacao propria desta classe, que desfaz, e so entao vira 400 aqui fora. Como a
 * transacao e nossa (o controlador nao abre nenhuma), nao ha transacao alheia abortada. Nao chamar de dentro de outra.
 */
@Service
public class CatalogoAdminServico {

	static final String NOME_DUPLICADO_PLANO = "Já existe um plano ativo com este nome.";
	static final String NOME_DUPLICADO_INSTITUICAO = "Já existe uma instituição ativa com este nome.";
	private static final String MIN_VALOR = "O valor precisa ser de pelo menos 1 centavo.";
	private static final String PASTORAL_NAO_ENCONTRADO = "Não encontramos esse item.";
	private static final String PASTORAL_TECNICO = "Este plano é usado pelo sistema e só aceita mudanças de nome e descrição.";

	private final PlanoRepository planos;
	private final InstituicaoRepository instituicoes;
	private final InstituicaoLogoRepository logos;
	private final Auditoria auditoria;
	private final TransactionTemplate transacao;

	CatalogoAdminServico(PlanoRepository planos, InstituicaoRepository instituicoes, InstituicaoLogoRepository logos,
			Auditoria auditoria, TransactionTemplate transacao) {
		this.planos = planos;
		this.instituicoes = instituicoes;
		this.logos = logos;
		this.auditoria = auditoria;
		this.transacao = transacao;
	}

	// --- leitura ---

	public List<Plano> listarPlanos() {
		return planos.findAllByOrderByOrdemAscIdAsc();
	}

	public List<Instituicao> listarInstituicoes() {
		return instituicoes.findAllByOrderByOrdemAscNomeAscIdAsc();
	}

	/** Id da instituicao para o sha256 da logo; so as que tem logo. */
	public Map<Long, String> hashesDeLogo(Collection<Long> ids) {
		if (ids.isEmpty()) return Map.of();
		return logos.hashes(ids).stream().collect(Collectors.toMap(h -> h.getInstituicaoId(), h -> h.getSha256()));
	}

	// --- planos ---

	public Plano criarPlano(Long autorId, String nome, String descricao, long valorCentavos,
			Complexidade complexidade, int ordem) {
		return gravar(NOME_DUPLICADO_PLANO, () -> {
			var p = planos.saveAndFlush(Plano.comum(nome.strip(), descricao, valorCentavos, complexidade, ordem));
			var diff = new LinkedHashMap<String, Object>();
			diff.put("nome", mudanca(null, p.getNome()));
			diff.put("descricao", mudanca(null, p.getDescricao()));
			diff.put("valorCentavos", mudanca(null, p.getValorCentavos()));
			diff.put("complexidade", mudanca(null, p.getComplexidade()));
			diff.put("ordem", mudanca(null, p.getOrdem()));
			auditoria.registrar(autorId, "plano", p.getId().toString(), Acao.ADICAO, diff);
			return p;
		});
	}

	/** Nulo = deixa como esta. Plano tecnico recusa tudo que nao for nome e descricao (400 plano_tecnico). */
	public Plano alterarPlano(Long autorId, Long id, String nome, String descricao, Long valorCentavos,
			Complexidade complexidade, Integer ordem, Boolean ativo) {
		return gravar(NOME_DUPLICADO_PLANO, () -> {
			var p = planos.travar(id).orElseThrow(CatalogoAdminServico::naoEncontrado);
			var diff = new LinkedHashMap<String, Object>();
			try {
				if (nome != null && !nome.strip().equals(p.getNome())
						|| descricao != null && !descricao.equals(p.getDescricao())) {
					var nomeAntes = p.getNome();
					var descricaoAntes = p.getDescricao();
					p.renomear(nome != null ? nome.strip() : nomeAntes, descricao != null ? descricao : descricaoAntes);
					if (!p.getNome().equals(nomeAntes)) diff.put("nome", mudanca(nomeAntes, p.getNome()));
					if (!p.getDescricao().equals(descricaoAntes)) diff.put("descricao", mudanca(descricaoAntes, p.getDescricao()));
				}
				if (valorCentavos != null && valorCentavos != p.getValorCentavos()) {
					if (valorCentavos < 1 && !p.isTecnico()) {
						throw new ErroDeNegocio(HttpStatus.BAD_REQUEST, "dados_invalidos", "Dados inválidos.",
								MIN_VALOR, List.of(new ErroDeCampo("valorCentavos", MIN_VALOR)));
					}
					var antes = p.getValorCentavos();
					p.alterarValor(valorCentavos);
					diff.put("valorCentavos", mudanca(antes, valorCentavos));
				}
				if (complexidade != null && complexidade != p.getComplexidade()) {
					var antes = p.getComplexidade();
					p.alterarComplexidade(complexidade);
					diff.put("complexidade", mudanca(antes, complexidade));
				}
				if (ordem != null && ordem != p.getOrdem()) {
					var antes = p.getOrdem();
					p.reordenar(ordem);
					diff.put("ordem", mudanca(antes, ordem));
				}
				if (ativo != null && ativo != p.isAtivo()) {
					if (ativo) p.ativar(); else p.desativar();
					diff.put("ativo", mudanca(!ativo, ativo));
				}
			} catch (PlanoTecnicoException e) {
				throw new ErroDeNegocio(HttpStatus.BAD_REQUEST, "plano_tecnico", e.getMessage(), PASTORAL_TECNICO);
			}
			if (diff.isEmpty()) return p;
			planos.flush();
			auditoria.registrar(autorId, "plano", id.toString(), Acao.MUDANCA, diff);
			return p;
		});
	}

	// --- instituicoes ---

	public Instituicao criarInstituicao(Long autorId, String nome, int ordem) {
		return gravar(NOME_DUPLICADO_INSTITUICAO, () -> {
			var i = instituicoes.saveAndFlush(Instituicao.nova(nome.strip(), ordem));
			var diff = new LinkedHashMap<String, Object>();
			diff.put("nome", mudanca(null, i.getNome()));
			diff.put("ordem", mudanca(null, i.getOrdem()));
			auditoria.registrar(autorId, "instituicao", i.getId().toString(), Acao.ADICAO, diff);
			return i;
		});
	}

	public Instituicao alterarInstituicao(Long autorId, Long id, String nome, Integer ordem, Boolean ativo) {
		return gravar(NOME_DUPLICADO_INSTITUICAO, () -> {
			var i = instituicoes.travar(id).orElseThrow(CatalogoAdminServico::naoEncontrado);
			var diff = new LinkedHashMap<String, Object>();
			if (nome != null && !nome.strip().equals(i.getNome())) {
				diff.put("nome", mudanca(i.getNome(), nome.strip()));
				i.renomear(nome.strip());
			}
			if (ordem != null && ordem != i.getOrdem()) {
				diff.put("ordem", mudanca(i.getOrdem(), ordem));
				i.reordenar(ordem);
			}
			if (ativo != null && ativo != i.isAtivo()) {
				diff.put("ativo", mudanca(i.isAtivo(), ativo));
				if (ativo) i.ativar(); else i.desativar();
			}
			if (diff.isEmpty()) return i;
			instituicoes.flush();
			auditoria.registrar(autorId, "instituicao", id.toString(), Acao.MUDANCA, diff);
			return i;
		});
	}

	// --- apoio ---

	/** Roda na transacao propria; a violacao do indice do nome ativo vira 400 depois do rollback. */
	private <T> T gravar(String mensagemDuplicado, Supplier<T> escrita) {
		try {
			return transacao.execute(s -> escrita.get());
		} catch (DataIntegrityViolationException e) {
			if (!violaNomeAtivo(e)) throw e;
			throw new ErroDeNegocio(HttpStatus.BAD_REQUEST, "dados_invalidos", "Dados inválidos.", mensagemDuplicado,
					List.of(new ErroDeCampo("nome", mensagemDuplicado)));
		}
	}

	private static boolean violaNomeAtivo(Throwable e) {
		for (var c = e; c != null; c = c.getCause()) {
			if (c instanceof org.hibernate.exception.ConstraintViolationException v && v.getConstraintName() != null
					&& v.getConstraintName().endsWith("_nome_ativo_ux")) return true;
		}
		return false;
	}

	private static Map<String, Object> mudanca(Object antes, Object depois) {
		var m = new LinkedHashMap<String, Object>();
		m.put("antes", antes instanceof Enum<?> e ? e.name() : antes);
		m.put("depois", depois instanceof Enum<?> e ? e.name() : depois);
		return m;
	}

	private static ErroDeNegocio naoEncontrado() {
		return new ErroDeNegocio(HttpStatus.NOT_FOUND, "nao_encontrado", PASTORAL_NAO_ENCONTRADO, PASTORAL_NAO_ENCONTRADO);
	}
}
