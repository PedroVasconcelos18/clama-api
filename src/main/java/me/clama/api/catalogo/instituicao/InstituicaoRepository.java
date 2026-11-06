package me.clama.api.catalogo.instituicao;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Meta.ordering do modelo: ordem, nome. */
public interface InstituicaoRepository extends JpaRepository<Instituicao, UUID> {
    List<Instituicao> findByAtivoTrueOrderByOrdemAscNomeAsc();

    List<Instituicao> findAllByOrderByOrdemAscNomeAsc();

    Optional<Instituicao> findByIdAndAtivoTrue(UUID id);
}
