package me.clama.api.identidade;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import me.clama.api.comum.cripto.CampoCriptografado;
import me.clama.api.comum.cripto.HashIdentificador;

/** Tabela users_user criada pelo Django; nenhuma mudanca de schema. */
@Entity
@org.hibernate.annotations.DynamicUpdate // so as colunas alteradas: escritores concorrentes e PII cifrada intocada
@Table(name = "users_user")
@EntityListeners(Usuario.RecalculaHashes.class)
public class Usuario {
    /** Mesmo comportamento do signal atualiza_hashes_user: vazio gera hash de "", nunca null. */
    public static class RecalculaHashes {
        private final HashIdentificador hash;

        public RecalculaHashes(HashIdentificador hash) {
            this.hash = hash;
        }

        @PrePersist
        @PreUpdate
        void recalcula(Usuario u) {
            u.emailHash = hash.hashEmail(u.email);
            u.cpfHash = hash.hashCpfCnpj(u.cpfCnpj);
            u.telefoneHash = hash.hashTelefone(u.telefone);
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "password")
    private String password = "";

    @Column(name = "last_login")
    private OffsetDateTime lastLogin;

    @Column(name = "is_superuser")
    private boolean superuser;

    @Column(name = "email")
    private String email = "";

    @Column(name = "is_staff")
    private boolean staff;

    @Column(name = "is_active")
    private boolean active = true;

    @Column(name = "date_joined")
    private OffsetDateTime dateJoined = OffsetDateTime.now();

    @Column(name = "nome_completo")
    private String nomeCompleto = "";

    @Column(name = "name")
    private String name = "";

    @Column(name = "is_clama_admin")
    private boolean clamaAdmin;

    @Convert(converter = CampoCriptografado.class)
    @Column(name = "cpf_cnpj")
    private String cpfCnpj;

    @Convert(converter = CampoCriptografado.class)
    @Column(name = "telefone")
    private String telefone;

    @Column(name = "force_change_password")
    private boolean forceChangePassword;

    @Column(name = "nome_format_blog")
    private String nomeFormatBlog = "compacto";

    @Column(name = "email_hash")
    private String emailHash = "";

    @Column(name = "cpf_hash")
    private String cpfHash;

    @Column(name = "telefone_hash")
    private String telefoneHash;

    @Column(name = "freemium_used_at")
    private OffsetDateTime freemiumUsedAt;

    @Column(name = "idade")
    private Integer idade;

    @Column(name = "sexo")
    private String sexo = "";

    public Long getId() {
        return id;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String v) {
        this.password = v;
    }

    public OffsetDateTime getLastLogin() {
        return lastLogin;
    }

    public void setLastLogin(OffsetDateTime v) {
        this.lastLogin = v;
    }

    public boolean isSuperuser() {
        return superuser;
    }

    public void setSuperuser(boolean v) {
        this.superuser = v;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String v) {
        this.email = v;
    }

    public boolean isStaff() {
        return staff;
    }

    public void setStaff(boolean v) {
        this.staff = v;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean v) {
        this.active = v;
    }

    public OffsetDateTime getDateJoined() {
        return dateJoined;
    }

    public void setDateJoined(OffsetDateTime v) {
        this.dateJoined = v;
    }

    public String getNomeCompleto() {
        return nomeCompleto;
    }

    public void setNomeCompleto(String v) {
        this.nomeCompleto = v;
    }

    public String getName() {
        return name;
    }

    public void setName(String v) {
        this.name = v;
    }

    public boolean isClamaAdmin() {
        return clamaAdmin;
    }

    public void setClamaAdmin(boolean v) {
        this.clamaAdmin = v;
    }

    public String getCpfCnpj() {
        return cpfCnpj;
    }

    public void setCpfCnpj(String v) {
        this.cpfCnpj = v;
    }

    public String getTelefone() {
        return telefone;
    }

    public void setTelefone(String v) {
        this.telefone = v;
    }

    public boolean isForceChangePassword() {
        return forceChangePassword;
    }

    public void setForceChangePassword(boolean v) {
        this.forceChangePassword = v;
    }

    public String getNomeFormatBlog() {
        return nomeFormatBlog;
    }

    public void setNomeFormatBlog(String v) {
        this.nomeFormatBlog = v;
    }

    public String getEmailHash() {
        return emailHash;
    }

    public void setEmailHash(String v) {
        this.emailHash = v;
    }

    public String getCpfHash() {
        return cpfHash;
    }

    public void setCpfHash(String v) {
        this.cpfHash = v;
    }

    public String getTelefoneHash() {
        return telefoneHash;
    }

    public void setTelefoneHash(String v) {
        this.telefoneHash = v;
    }

    public OffsetDateTime getFreemiumUsedAt() {
        return freemiumUsedAt;
    }

    public void setFreemiumUsedAt(OffsetDateTime v) {
        this.freemiumUsedAt = v;
    }

    public Integer getIdade() {
        return idade;
    }

    public void setIdade(Integer v) {
        this.idade = v;
    }

    public String getSexo() {
        return sexo;
    }

    public void setSexo(String v) {
        this.sexo = v;
    }
}
