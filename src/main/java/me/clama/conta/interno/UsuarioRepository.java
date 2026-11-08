package me.clama.conta.interno;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface UsuarioRepository extends JpaRepository<Usuario, Long>, JpaSpecificationExecutor<Usuario> {

	Optional<Usuario> findByEmail(String emailMinusculo);

	Optional<Usuario> findByEmailCanonico(String emailCanonico);

	long countByPapelAndAtivoTrue(Papel papel);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from Usuario u where u.id = :id")
	Optional<Usuario> travar(Long id);

	/** Cria um CLIENTE sem senha; 0 se o e-mail canonico ja existe: sem excecao, a transacao do chamador segue sa. */
	@Modifying(flushAutomatically = true)
	@Query(nativeQuery = true, value = """
			insert into usuario (email, email_canonico, nome_completo, papel, ativo, criado_em)
			values (:email, :canonico, :nome, 'CLIENTE', true, :criadoEm) on conflict (email_canonico) do nothing""")
	int inserirSeAusente(String email, String canonico, String nome, Instant criadoEm);
}
