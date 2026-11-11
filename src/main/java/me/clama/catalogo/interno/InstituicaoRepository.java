package me.clama.catalogo.interno;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstituicaoRepository extends JpaRepository<Instituicao, Long> {

	Optional<Instituicao> findByIdAndAtivoTrue(Long id);

	List<Instituicao> findByAtivoTrueOrderByOrdemAscNomeAscIdAsc();

	List<Instituicao> findAllByOrderByOrdemAscNomeAscIdAsc();
}
