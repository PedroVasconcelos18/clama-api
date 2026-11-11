package me.clama.catalogo.interno;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface InstituicaoLogoRepository extends JpaRepository<InstituicaoLogo, Long> {

	/** Id e hash das logos, sem o conteudo (a lista publica nao carrega blobs). */
	@Query("select l.instituicaoId as instituicaoId, l.sha256 as sha256 from InstituicaoLogo l where l.instituicaoId in :ids")
	List<HashDeLogo> hashes(Collection<Long> ids);

	interface HashDeLogo {
		Long getInstituicaoId();

		String getSha256();
	}
}
