package me.clama.api.documento;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DocumentoRepository extends JpaRepository<DocumentoContexto, UUID> {
    /** Meta.ordering = -created_at. */
    List<DocumentoContexto> findAllByOrderByCreatedAtDesc();

    /** ativos_sincronizados() do manager: contexto da geracao de oracao (Etapa 4). */
    @Query("select d from DocumentoContexto d where d.ativo = true and d.anthropicFileId is not null"
            + " and d.anthropicFileId <> '' order by d.createdAt desc")
    List<DocumentoContexto> ativosSincronizados();
}
