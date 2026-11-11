package me.clama.pagamento.interno;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface CobrancaRepository extends JpaRepository<Cobranca, UUID> {}
