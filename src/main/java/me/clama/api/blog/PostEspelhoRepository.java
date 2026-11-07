package me.clama.api.blog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Sem delete de proposito: o espelho nunca se apaga (RemocaoDeEspelhoProibida do Django). */
public interface PostEspelhoRepository extends Repository<PostEspelho, UUID> {
    PostEspelho save(PostEspelho espelho);

    Optional<PostEspelho> findById(UUID id);

    Optional<PostEspelho> findByWpPostId(int wpPostId);

    List<PostEspelho> findBySlug(String slug);
}
