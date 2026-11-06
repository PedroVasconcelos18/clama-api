package me.clama.api.prompt;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PromptTemplateRepository extends JpaRepository<PromptTemplate, UUID> {
    /** Meta.ordering = -versao; created_at desempata (o Postgres nao garante ordem em empate). */
    List<PromptTemplate> findAllByOrderByVersaoDescCreatedAtAsc();

    boolean existsByNome(String nome);

    @Query("select max(p.versao) from PromptTemplate p where p.nome = :nome")
    Optional<Integer> maxVersaoPorNome(String nome);

    /** get_active() do Django; geracao de oracao (Etapa 4). */
    List<PromptTemplate> findByAtivoTrue();

    /** Nenhum ativo: vazio (a Etapa 4 mapeia para no_active_prompt); mais de um: MultipleObjectsReturned do Django. */
    default Optional<PromptTemplate> buscarAtivo() {
        List<PromptTemplate> ativos = findByAtivoTrue();
        if (ativos.size() > 1) {
            throw new IllegalStateException("mais de um PromptTemplate ativo");
        }
        return ativos.stream().findFirst();
    }

    @Modifying
    @Query("update PromptTemplate p set p.ativo = false where p.ativo = true and p.id <> :id")
    int desativarOutros(UUID id);

    /** Serializa as ativacoes: so uma transacao por vez decide quem e o ativo. */
    @Query(value = "select pg_advisory_xact_lock(hashtext('prompts_prompttemplate.ativo'))", nativeQuery = true)
    void travarAtivacao();
}
