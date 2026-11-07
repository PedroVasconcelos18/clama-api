package me.clama.api.blog;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import me.clama.api.identidade.Usuario;

/** blog_reacao (so "like"). Unique (post_espelho_id, customer_id, tipo) no banco; post_id legado fora (R1). */
@Entity
@Table(name = "blog_reacao")
public class Reacao extends EntidadeDoBlog {
    public static final String LIKE = "like";

    private String tipo = LIKE;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id")
    private Usuario customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_espelho_id")
    private PostEspelho postEspelho;

    public String getTipo() {
        return tipo;
    }

    public void setTipo(String v) {
        tipo = v;
    }

    public Usuario getCustomer() {
        return customer;
    }

    public void setCustomer(Usuario v) {
        customer = v;
    }

    public PostEspelho getPostEspelho() {
        return postEspelho;
    }

    public void setPostEspelho(PostEspelho v) {
        postEspelho = v;
    }
}
