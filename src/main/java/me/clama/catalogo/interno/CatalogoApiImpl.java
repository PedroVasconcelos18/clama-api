package me.clama.catalogo.interno;

import io.sentry.Sentry;
import java.util.List;
import java.util.Optional;
import me.clama.catalogo.CatalogoApi;
import me.clama.catalogo.InstituicaoResumo;
import me.clama.catalogo.PlanoResumo;
import me.clama.catalogo.TipoDePlano;
import me.clama.infra.web.ErroDeNegocio;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class CatalogoApiImpl implements CatalogoApi {

	private final PlanoRepository planos;
	private final InstituicaoRepository instituicoes;
	private final InferenciaDePlano inferencia;

	CatalogoApiImpl(PlanoRepository planos, InstituicaoRepository instituicoes, InferenciaDePlano inferencia) {
		this.planos = planos;
		this.instituicoes = instituicoes;
		this.inferencia = inferencia;
	}

	@Override
	public Optional<PlanoResumo> planoAtivo(Long id) {
		return planos.findByIdAndTipoAndAtivoTrue(id, TipoDePlano.COMUM).map(Plano::resumo);
	}

	@Override
	public PlanoResumo planoGratuito() {
		return planos.findByTipoAndAtivoTrue(TipoDePlano.GRATUITO).map(Plano::resumo).orElseThrow(() -> {
			var e = new ErroDeNegocio(HttpStatus.SERVICE_UNAVAILABLE, "freemium_indisponivel",
					"Plano freemium não configurado", "Estamos com problemas momentâneos. Tente em alguns minutos.");
			Sentry.captureException(e);
			return e;
		});
	}

	@Override
	public PlanoResumo planoValorLivre() {
		return planos.findByTipoAndAtivoTrue(TipoDePlano.VALOR_LIVRE).map(Plano::resumo)
				.orElseThrow(CatalogoApiImpl::semPlano);
	}

	@Override
	public PlanoResumo inferirPlano(long valorCentavos) {
		return inferencia.inferirPorValor(valorCentavos).map(Plano::resumo).orElseGet(this::planoValorLivre);
	}

	@Override
	public Optional<InstituicaoResumo> instituicaoAtiva(Long id) {
		return instituicoes.findByIdAndAtivoTrue(id).map(Instituicao::resumo);
	}

	@Override
	public List<InstituicaoResumo> instituicoesAtivas() {
		return instituicoes.findByAtivoTrueOrderByOrdemAscNomeAscIdAsc().stream().map(Instituicao::resumo).toList();
	}

	private static ErroDeNegocio semPlano() {
		return new ErroDeNegocio(HttpStatus.BAD_REQUEST, "sem_plano_disponivel", "Nenhum plano disponível no momento.",
				"Nenhum plano disponível no momento.");
	}
}
