package me.clama.catalogo.interno;

import java.util.Optional;
import me.clama.catalogo.TipoDePlano;
import org.springframework.stereotype.Component;

/** INV:62: o plano comum que o valor doado alcanca. */
@Component
class InferenciaDePlano {

	private final PlanoRepository planos;

	InferenciaDePlano(PlanoRepository planos) {
		this.planos = planos;
	}

	/** COMUM ativo de maior valor ate o doado; senao o de menor valor; senao vazio. */
	Optional<Plano> inferirPorValor(long valorCentavos) {
		return planos.findFirstByTipoAndAtivoTrueAndValorCentavosLessThanEqualOrderByValorCentavosDescIdAsc(
				TipoDePlano.COMUM, valorCentavos)
				.or(() -> planos.findFirstByTipoAndAtivoTrueOrderByValorCentavosAscIdAsc(TipoDePlano.COMUM));
	}
}
