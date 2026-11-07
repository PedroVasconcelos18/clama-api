package me.clama.api.pedido;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import me.clama.api.catalogo.instituicao.Instituicao;
import me.clama.api.catalogo.plano.Plano;
import me.clama.api.comum.Dinheiro;
import me.clama.api.comum.cripto.CampoCriptografado;
import me.clama.api.comum.erro.PastoralException;
import me.clama.api.identidade.Usuario;
import org.hibernate.annotations.ColumnTransformer;

/**
 * Tabela orders_pedido criada pelo Django; nenhuma mudanca de schema. Os defaults sao os do model (campo cifrado
 * vazio e gravado como token Fernet de "", como o django-encrypted-model-fields).
 */
@Entity
@org.hibernate.annotations.DynamicUpdate // so as colunas alteradas: escritores concorrentes e PII cifrada intocada
@Table(name = "orders_pedido")
public class Pedido {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plano_id")
    private Plano plano;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "instituicao_id")
    private Instituicao instituicao;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private Usuario user;


    @Convert(converter = CampoCriptografado.class)
    private String nome = "";

    @Convert(converter = CampoCriptografado.class)
    private String email = "";

    @Convert(converter = CampoCriptografado.class)
    private String telefone = "";

    private Short idade;

    private String sexo = "";

    @Convert(converter = CampoCriptografado.class)
    @Column(name = "pedido_oracao")
    private String pedidoOracao = "";

    @Convert(converter = CampoCriptografado.class)
    @Column(name = "oracao_gerada")
    private String oracaoGerada = "";

    @Column(name = "valor_centavos")
    private int valorCentavos;

    @Convert(converter = CanalEntrega.Conversor.class)
    @Column(name = "canal_entrega")
    private CanalEntrega canalEntrega = CanalEntrega.EMAIL;

    @Convert(converter = StatusPedido.Conversor.class)
    private StatusPedido status = StatusPedido.AGUARDANDO_PAGAMENTO;

    @Column(name = "consent_aceito")
    private boolean consentAceito = false;

    @Column(name = "consent_aceito_at")
    private OffsetDateTime consentAceitoAt;

    @Column(name = "consent_ip", columnDefinition = "inet")
    @ColumnTransformer(read = "host(consent_ip)", write = "cast(? as inet)")
    private String consentIp;

    @Column(name = "consent_versao")
    private String consentVersao = "";

    @Column(name = "last_error")
    private String lastError = "";

    @Column(name = "retry_count")
    private short retryCount = 0;

    @Column(name = "whatsapp_delivered_at")
    private OffsetDateTime whatsappDeliveredAt;

    @Column(name = "whatsapp_message_id")
    private String whatsappMessageId = "";

    @Column(name = "whatsapp_read_at")
    private OffsetDateTime whatsappReadAt;

    @Convert(converter = CampoCriptografado.class)
    @Column(name = "cpf_cnpj")
    private String cpfCnpj = "";

    @Column(name = "eh_gratuito")
    private boolean ehGratuito = false;

    @Column(name = "device_hash")
    private String deviceHash = "";

    @Column(name = "oracao_email_sent_at")
    private OffsetDateTime oracaoEmailSentAt;

    @Column(name = "provider_payment_id")
    private String providerPaymentId;

    @Column(name = "provider_checkout_url")
    private String providerCheckoutUrl;

    @Column(name = "pix_qr_code")
    private String pixQrCode;

    @Column(name = "pix_qr_code_base64")
    private String pixQrCodeBase64;

    @PrePersist
    void criado() {
        // micros: o timestamptz guarda micro e a resposta mostra o mesmo valor que ficou no banco
        createdAt = updatedAt = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    /** auto_now: todo save() regrava updated_at. */
    @PreUpdate
    void atualizado() {
        updatedAt = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    /** Pedido.marcar_como_pago: so a partir de aguardando_pagamento. */
    public void marcarComoPago() {
        if (status != StatusPedido.AGUARDANDO_PAGAMENTO) {
            throw new TransicaoInvalida("Pedido nao esta aguardando pagamento");
        }
        status = StatusPedido.PAGO;
    }

    /** Pedido.marcar_como_gratuito (fluxo admin). false: ja gratuito e gerando, no-op (nao re-disparar). */
    public boolean marcarComoGratuito() {
        if (status == StatusPedido.ENVIADA) {
            throw new PastoralException(409, "invalid_state_transition",
                    "Pedido já enviado não pode ser marcado como gratuito",
                    "Esse pedido já foi enviado — não dá para marcá-lo como gratuito.");
        }
        if (ehGratuito && status == StatusPedido.GERANDO_ORACAO) {
            return false;
        }
        ehGratuito = true;
        valorCentavos = 0;
        status = StatusPedido.GERANDO_ORACAO;
        return true;
    }

    public String valorReaisStr() {
        return Dinheiro.centavosParaReaisStr(valorCentavos);
    }

    public UUID getId() {
        return id;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public Plano getPlano() {
        return plano;
    }

    public void setPlano(Plano v) {
        plano = v;
    }

    public Instituicao getInstituicao() {
        return instituicao;
    }

    public void setInstituicao(Instituicao v) {
        instituicao = v;
    }

    public Usuario getUser() {
        return user;
    }

    public void setUser(Usuario v) {
        user = v;
    }

    public String getNome() {
        return nome;
    }

    public void setNome(String v) {
        nome = v;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String v) {
        email = v;
    }

    public String getTelefone() {
        return telefone;
    }

    public void setTelefone(String v) {
        telefone = v;
    }

    public Integer getIdade() {
        return idade == null ? null : idade.intValue();
    }

    public void setIdade(Integer v) {
        idade = v == null ? null : v.shortValue();
    }

    public String getSexo() {
        return sexo;
    }

    public void setSexo(String v) {
        sexo = v;
    }

    public String getPedidoOracao() {
        return pedidoOracao;
    }

    public void setPedidoOracao(String v) {
        pedidoOracao = v;
    }

    public String getOracaoGerada() {
        return oracaoGerada;
    }

    public void setOracaoGerada(String v) {
        oracaoGerada = v;
    }

    public int getValorCentavos() {
        return valorCentavos;
    }

    public void setValorCentavos(int v) {
        valorCentavos = v;
    }

    public CanalEntrega getCanalEntrega() {
        return canalEntrega;
    }

    public void setCanalEntrega(CanalEntrega v) {
        canalEntrega = v;
    }

    public StatusPedido getStatus() {
        return status;
    }

    public void setStatus(StatusPedido v) {
        status = v;
    }

    public boolean isConsentAceito() {
        return consentAceito;
    }

    public void setConsentAceito(boolean v) {
        consentAceito = v;
    }

    public OffsetDateTime getConsentAceitoAt() {
        return consentAceitoAt;
    }

    public void setConsentAceitoAt(OffsetDateTime v) {
        consentAceitoAt = v;
    }

    public String getConsentIp() {
        return consentIp;
    }

    public void setConsentIp(String v) {
        consentIp = v;
    }

    public String getConsentVersao() {
        return consentVersao;
    }

    public void setConsentVersao(String v) {
        consentVersao = v;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String v) {
        lastError = v;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int v) {
        retryCount = (short) v;
    }

    public OffsetDateTime getWhatsappDeliveredAt() {
        return whatsappDeliveredAt;
    }

    public void setWhatsappDeliveredAt(OffsetDateTime v) {
        whatsappDeliveredAt = v;
    }

    public String getWhatsappMessageId() {
        return whatsappMessageId;
    }

    public void setWhatsappMessageId(String v) {
        whatsappMessageId = v;
    }

    public OffsetDateTime getWhatsappReadAt() {
        return whatsappReadAt;
    }

    public void setWhatsappReadAt(OffsetDateTime v) {
        whatsappReadAt = v;
    }

    public String getCpfCnpj() {
        return cpfCnpj;
    }

    public void setCpfCnpj(String v) {
        cpfCnpj = v;
    }

    public boolean isEhGratuito() {
        return ehGratuito;
    }

    public void setEhGratuito(boolean v) {
        ehGratuito = v;
    }

    public String getDeviceHash() {
        return deviceHash;
    }

    public void setDeviceHash(String v) {
        deviceHash = v;
    }

    public OffsetDateTime getOracaoEmailSentAt() {
        return oracaoEmailSentAt;
    }

    public void setOracaoEmailSentAt(OffsetDateTime v) {
        oracaoEmailSentAt = v;
    }

    public String getProviderPaymentId() {
        return providerPaymentId;
    }

    public void setProviderPaymentId(String v) {
        providerPaymentId = v;
    }

    public String getProviderCheckoutUrl() {
        return providerCheckoutUrl;
    }

    public void setProviderCheckoutUrl(String v) {
        providerCheckoutUrl = v;
    }

    public String getPixQrCode() {
        return pixQrCode;
    }

    public void setPixQrCode(String v) {
        pixQrCode = v;
    }

    public String getPixQrCodeBase64() {
        return pixQrCodeBase64;
    }

    public void setPixQrCodeBase64(String v) {
        pixQrCodeBase64 = v;
    }
}
