package me.clama.catalogo;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Interface do modulo para os outros (pedido, oracao, freemium, relatorios): so leitura. */
public interface CatalogoApi {

	/** Plano COMUM ativo (o que o doador pode escolher); vazio se nao existe, esta inativo ou e tecnico. */
	Optional<PlanoResumo> planoAtivo(Long id);

	/** Plano do freemium; ausente = Sentry + 503 {@code freemium_indisponivel}. */
	PlanoResumo planoGratuito();

	/** Plano de doacao sem plano escolhido. */
	PlanoResumo planoValorLivre();

	/**
	 * Inferencia (INV:62): COMUM ativo de maior valor ate o doado; senao o de menor valor; senao o de valor livre;
	 * senao 400 {@code sem_plano_disponivel}.
	 */
	PlanoResumo inferirPlano(long valorCentavos);

	/** Instituicao para um pedido novo; vazio se nao existe ou esta inativa. */
	Optional<InstituicaoResumo> instituicaoAtiva(Long id);

	/** Todas as ativas, em ordem de exibicao. */
	List<InstituicaoResumo> instituicoesAtivas();

	/** Planos por id, inclusive inativos e tecnicos (pedido antigo aponta para eles); ids inexistentes ficam de fora. */
	Map<Long, PlanoResumo> planos(Collection<Long> ids);

	/** Instituicoes por id, inclusive inativas; ids inexistentes ficam de fora. */
	Map<Long, InstituicaoResumo> instituicoes(Collection<Long> ids);
}
