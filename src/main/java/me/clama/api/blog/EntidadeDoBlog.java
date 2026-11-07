package me.clama.api.blog;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;

/**
 * id uuid + created_at/updated_at das tabelas do blog. As datas vem do {@link Clock} injetado (o contrato congela o
 * relogio): o listener recebe o Clock por construtor, do mesmo jeito que o Hibernate recebe o HashIdentificador do
 * {@code Usuario}. Microssegundos, que e o que o Postgres guarda.
 */
@MappedSuperclass
@EntityListeners(EntidadeDoBlog.Carimbo.class)
public abstract class EntidadeDoBlog {
    public static class Carimbo {
        private final Clock relogio;

        /** ObjectProvider: os testes de slice JPA que nao importam o RelogioConfig continuam subindo (relogio do sistema). */
        public Carimbo(ObjectProvider<Clock> relogio) {
            this.relogio = relogio.getIfAvailable(Clock::systemUTC);
        }

        @PrePersist
        void criado(EntidadeDoBlog e) {
            e.createdAt = e.updatedAt = agora();
            if (e instanceof CustomerBanido b && b.getBanidoEm() == null) {
                b.setBanidoEm(e.createdAt);
            }
        }

        @PreUpdate
        void atualizado(EntidadeDoBlog e) {
            e.updatedAt = agora();
        }

        private OffsetDateTime agora() {
            return EntidadeDoBlog.agora(relogio);
        }
    }

    /** O "agora" das colunas do blog (UTC, microssegundos), tambem para quem grava por SQL. */
    public static OffsetDateTime agora(Clock relogio) {
        return OffsetDateTime.now(relogio).withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    /** save() do Django grava sempre (auto_now): marca a linha como alterada mesmo sem campo novo. */
    public void marcarAlterada() {
        updatedAt = null; // o @PreUpdate do Carimbo poe o agora do relogio
    }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public UUID getId() {
        return id;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
