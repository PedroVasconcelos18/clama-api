package me.clama.catalogo.interno;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface InstituicaoRepository extends JpaRepository<Instituicao, Long> {

	Optional<Instituicao> findByIdAndAtivoTrue(Long id);

	List<Instituicao> findByAtivoTrueOrderByOrdemAscNomeAscIdAsc();

	List<Instituicao> findAllByOrderByOrdemAscNomeAscIdAsc();

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select x from Instituicao x where x.id = :id")
	Optional<Instituicao> travar(Long id);
}
