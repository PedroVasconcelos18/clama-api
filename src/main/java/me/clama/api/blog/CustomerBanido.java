package me.clama.api.blog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import me.clama.api.identidade.Usuario;

/**
 * blog_customerbanido. banido_em e preenchido na criacao pelo Clock (auto_now_add); revogar = preencher
 * revogado_em/revogado_por (quem revoga passa a data do Clock), nunca apagar.
 */
@Entity
@Table(name = "blog_customerbanido")
public class CustomerBanido extends EntidadeDoBlog {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id")
    private Usuario customer;

    private String motivo = "";

    @Column(name = "banido_em")
    private OffsetDateTime banidoEm;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "banido_por_id")
    private Usuario banidoPor;

    @Column(name = "revogado_em")
    private OffsetDateTime revogadoEm;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "revogado_por_id")
    private Usuario revogadoPor;

    public Usuario getCustomer() {
        return customer;
    }

    public void setCustomer(Usuario v) {
        customer = v;
    }

    public String getMotivo() {
        return motivo;
    }

    public void setMotivo(String v) {
        motivo = v;
    }

    public OffsetDateTime getBanidoEm() {
        return banidoEm;
    }

    void setBanidoEm(OffsetDateTime v) {
        banidoEm = v;
    }

    public Usuario getBanidoPor() {
        return banidoPor;
    }

    public void setBanidoPor(Usuario v) {
        banidoPor = v;
    }

    public OffsetDateTime getRevogadoEm() {
        return revogadoEm;
    }

    public Usuario getRevogadoPor() {
        return revogadoPor;
    }

    public void revogar(Usuario por, OffsetDateTime em) {
        revogadoPor = por;
        revogadoEm = em;
    }
}
