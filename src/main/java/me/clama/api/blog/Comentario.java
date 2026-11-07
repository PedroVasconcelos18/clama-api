package me.clama.api.blog;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.util.UUID;
import me.clama.api.comum.cripto.CampoCriptografado;
import me.clama.api.identidade.Usuario;

/**
 * blog_comentario. is_suspeito e recalculado em todo save (pre_save do Django). post_id (FK legada de blog_post, R1)
 * fica fora do mapeamento: a coluna e nullable e o CHECK e satisfeito por post_espelho_id.
 */
@Entity
@Table(name = "blog_comentario")
public class Comentario extends EntidadeDoBlog {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id")
    private Usuario customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_espelho_id")
    private PostEspelho postEspelho;

    private String conteudo;

    /** Fernet, inclusive "" (a coluna nunca guarda texto vazio). */
    @Convert(converter = CampoCriptografado.class)
    @Column(name = "ip_address")
    private String ipAddress = "";

    @Column(name = "is_suspeito")
    private boolean suspeito;

    @PrePersist
    @PreUpdate
    void moderar() {
        suspeito = Moderacao.ehSuspeito(conteudo);
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

    public String getConteudo() {
        return conteudo;
    }

    public void setConteudo(String v) {
        conteudo = v;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String v) {
        ipAddress = v;
    }

    public boolean isSuspeito() {
        return suspeito;
    }
}
