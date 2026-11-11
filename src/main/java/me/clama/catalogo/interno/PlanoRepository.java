package me.clama.catalogo.interno;

import java.util.List;
import java.util.Optional;
import me.clama.catalogo.TipoDePlano;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanoRepository extends JpaRepository<Plano, Long> {

	Optional<Plano> findByIdAndTipoAndAtivoTrue(Long id, TipoDePlano tipo);

	Optional<Plano> findByTipoAndAtivoTrue(TipoDePlano tipo);

	List<Plano> findByTipoAndAtivoTrueOrderByOrdemAscIdAsc(TipoDePlano tipo);

	List<Plano> findAllByOrderByOrdemAscIdAsc();

	Optional<Plano> findFirstByTipoAndAtivoTrueAndValorCentavosLessThanEqualOrderByValorCentavosDescIdAsc(
			TipoDePlano tipo, long valorCentavos);

	Optional<Plano> findFirstByTipoAndAtivoTrueOrderByValorCentavosAscIdAsc(TipoDePlano tipo);
}
