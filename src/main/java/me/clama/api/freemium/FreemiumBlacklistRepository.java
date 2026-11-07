package me.clama.api.freemium;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FreemiumBlacklistRepository extends JpaRepository<FreemiumBlacklist, UUID> {
    /**
     * Camada permanente do submit e re-check da confirmacao: qualquer um dos tres hashes, ou o device (so quando
     * informado; nulo e vazio nao casam, como o "if device_hash" do Django).
     */
    @Query("""
            select count(b) > 0 from FreemiumBlacklist b where b.cpfHash = ?1 or b.emailHash = ?2
              or b.telefoneHash = ?3 or (?4 is not null and ?4 <> '' and b.deviceHash = ?4)""")
    boolean existeAlgum(String cpfHash, String emailHash, String telefoneHash, String deviceHashOuNull);

    /** Camada de IP: entradas do mesmo ip_hash com created_at >= desde (created_at__gte). */
    @Query("select count(b) from FreemiumBlacklist b where b.ipHash = ?1 and b.createdAt >= ?2")
    long contarPorIpDesde(String ipHash, OffsetDateTime desde);
}
