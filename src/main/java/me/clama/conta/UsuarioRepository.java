package me.clama.conta;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

	Optional<Usuario> findByEmail(String emailMinusculo);

	Optional<Usuario> findByEmailCanonico(String emailCanonico);

	long countByPapelAndAtivoTrue(Papel papel);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from Usuario u where u.id = :id")
	Optional<Usuario> travar(Long id);
}
