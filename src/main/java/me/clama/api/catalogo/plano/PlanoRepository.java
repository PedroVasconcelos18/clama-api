package me.clama.api.catalogo.plano;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanoRepository extends JpaRepository<Plano, UUID> {
    List<Plano> findByAtivoTrueAndVisivelTrueOrderByOrdem();

    List<Plano> findAllByOrderByOrdem();

    boolean existsByNomeAndAtivoTrue(String nome);

    boolean existsByNomeAndAtivoTrueAndIdNot(String nome, UUID id);
}
