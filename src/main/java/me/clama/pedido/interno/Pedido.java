package me.clama.pedido.interno;

import static me.clama.pedido.StatusPedido.*;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import me.clama.infra.cripto.CampoCifrado;
import me.clama.pedido.StatusPedido;
import me.clama.pedido.TransicaoInvalidaException;
import org.hibernate.annotations.DynamicUpdate;

/**
 * O status so muda pelos metodos de intencao abaixo. {@code @DynamicUpdate}: mudar o status nao regrava as colunas
 * cifradas (cada cifragem tem nonce novo, entao regravar trocaria o texto cifrado de todas).
 */
@Entity
@Table(name = "pedido")
@DynamicUpdate
public class Pedido {

	/** Dados de um pedido novo; os HMACs vem prontos (a cifra e a busca nascem no servico de criacao). */
	public record Dados(Long usuarioId, Long planoId, Long instituicaoId, long valorCentavos, String nome, String email,
			String emailHmac, String cpfCnpj, String cpfHmac, String telefone, Short idade, String sexo, String pedidoOracao,
			Instant consentidoEm, String consentimentoIp) {}

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	private Long usuarioId;
	private Long planoId;
	private Long instituicaoId;

	@Enumerated(EnumType.STRING)
	private StatusPedido status;

	private long valorCentavos;
	private boolean gratuito;

	@Convert(converter = CampoCifrado.class)
	private String nome;

	@Convert(converter = CampoCifrado.class)
	private String email;

	private String emailHmac;

	@Convert(converter = CampoCifrado.class)
	private String cpfCnpj;

	private String cpfHmac;

	@Convert(converter = CampoCifrado.class)
	private String telefone;

	private Short idade;
	private String sexo;

	@Convert(converter = CampoCifrado.class)
	private String pedidoOracao;

	@Convert(converter = CampoCifrado.class)
	private String oracao;

	private Instant consentidoEm;
	private String consentimentoIp;
	private String ultimoErro;
	private int tentativas;
	private Instant criadoEm;
	private Instant atualizadoEm;

	protected Pedido() {}

	/** Pedido pago depois: nasce AGUARDANDO_PAGAMENTO. */
	public static Pedido novo(Dados d) {
		if (d.valorCentavos() < 0) throw new IllegalArgumentException("Valor negativo.");
		var p = new Pedido();
		p.usuarioId = d.usuarioId();
		p.planoId = d.planoId();
		p.instituicaoId = d.instituicaoId();
		p.valorCentavos = d.valorCentavos();
		p.nome = d.nome();
		p.email = d.email();
		p.emailHmac = d.emailHmac();
		p.cpfCnpj = d.cpfCnpj();
		p.cpfHmac = d.cpfHmac();
		p.telefone = d.telefone();
		p.idade = d.idade();
		p.sexo = d.sexo();
		p.pedidoOracao = d.pedidoOracao();
		p.consentidoEm = d.consentidoEm();
		p.consentimentoIp = d.consentimentoIp();
		p.status = AGUARDANDO_PAGAMENTO;
		p.criadoEm = p.atualizadoEm = agora();
		return p;
	}

	/** Pedido gratuito do logado: nasce PAGO ("pronto para gerar"), valor 0. */
	public static Pedido gratuito(Dados d) {
		var p = novo(d);
		p.concederGratuidade();
		return p;
	}

	private static Instant agora() {
		return Instant.now().truncatedTo(ChronoUnit.MICROS); // o timestamptz guarda micro
	}

	private void ir(StatusPedido para, String acao, Set<StatusPedido> de) {
		if (!de.contains(status)) throw new TransicaoInvalidaException(status, acao);
		status = para;
		atualizadoEm = agora();
	}

	public void confirmarPagamento() {
		ir(PAGO, "confirmar pagamento", EnumSet.of(AGUARDANDO_PAGAMENTO));
	}

	public void concederGratuidade() {
		ir(PAGO, "conceder gratuidade", EnumSet.of(AGUARDANDO_PAGAMENTO));
		gratuito = true;
		valorCentavos = 0;
	}

	public void iniciarGeracao() {
		ir(GERANDO_ORACAO, "iniciar geracao", EnumSet.of(PAGO, AGUARDANDO_NOVA_TENTATIVA, GERANDO_ORACAO));
	}

	public void registrarOracao(String texto) {
		if (texto == null || texto.isBlank()) throw new IllegalArgumentException("Oracao vazia.");
		ir(ORACAO_GERADA, "registrar oracao", EnumSet.of(GERANDO_ORACAO));
		oracao = texto;
	}

	public void agendarNovaTentativa(String erro) {
		ir(AGUARDANDO_NOVA_TENTATIVA, "agendar nova tentativa", EnumSet.of(GERANDO_ORACAO));
		tentativas++;
		ultimoErro = erro;
	}

	public void falhar(String motivo) {
		ir(ERRO, "falhar", EnumSet.of(PAGO, GERANDO_ORACAO, AGUARDANDO_NOVA_TENTATIVA, ORACAO_GERADA));
		ultimoErro = motivo;
	}

	/** No reenvio o pedido ja esta ENVIADA: fica como esta. */
	public void marcarEnviado() {
		ir(ENVIADA, "marcar enviado", EnumSet.of(ORACAO_GERADA, ENVIADA));
	}

	/** ERRO com oracao volta a ORACAO_GERADA; ENVIADA e ORACAO_GERADA ficam como estao. */
	public void solicitarReenvio() {
		if (oracao == null) throw new TransicaoInvalidaException(status, "reenviar sem oracao");
		ir(status == ERRO ? ORACAO_GERADA : status, "reenviar", EnumSet.of(ENVIADA, ORACAO_GERADA, ERRO));
	}

	/** ERRO sem oracao volta a PAGO, com tentativas e erro zerados. */
	public void solicitarRegeneracao() {
		if (oracao != null) throw new TransicaoInvalidaException(status, "regenerar oracao ja gerada");
		ir(PAGO, "regenerar", EnumSet.of(ERRO));
		tentativas = 0;
		ultimoErro = null;
	}

	/** Vincula o doador anonimo a conta criada ou achada no pagamento. */
	public void vincularUsuario(Long usuarioId) {
		this.usuarioId = usuarioId;
	}

	public UUID getId() { return id; }
	public Long getUsuarioId() { return usuarioId; }
	public Long getPlanoId() { return planoId; }
	public Long getInstituicaoId() { return instituicaoId; }
	public StatusPedido getStatus() { return status; }
	public long getValorCentavos() { return valorCentavos; }
	public boolean isGratuito() { return gratuito; }
	public String getNome() { return nome; }
	public String getEmail() { return email; }
	public String getEmailHmac() { return emailHmac; }
	public String getCpfCnpj() { return cpfCnpj; }
	public String getCpfHmac() { return cpfHmac; }
	public String getTelefone() { return telefone; }
	public Short getIdade() { return idade; }
	public String getSexo() { return sexo; }
	public String getPedidoOracao() { return pedidoOracao; }
	public String getOracao() { return oracao; }
	public Instant getConsentidoEm() { return consentidoEm; }
	public String getConsentimentoIp() { return consentimentoIp; }
	public String getUltimoErro() { return ultimoErro; }
	public int getTentativas() { return tentativas; }
	public Instant getCriadoEm() { return criadoEm; }
	public Instant getAtualizadoEm() { return atualizadoEm; }
}
