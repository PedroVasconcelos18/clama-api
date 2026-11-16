package me.clama.virada;

import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import me.clama.infra.auditoria.Acao;
import me.clama.infra.auditoria.Auditoria;
import me.clama.infra.cripto.Cifra;
import me.clama.infra.cripto.HmacDeIdentificador;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * Carrega o JSON do extrator ({@code extrair_django.py}: vocabulario do Django, PII em claro) num banco novo, numa
 * transacao so. Cifra e HMAC sao os do app ({@link Cifra}, que o {@code CampoCifrado} usa, e {@link HmacDeIdentificador},
 * com as chaves do ambiente); o e-mail canonico e o {@code normalizarEmail} que o {@code EmailCanonico} usa. SQL e nao as
 * entidades porque o {@code Pedido} so muda de status por transicao e aqui o status e historico. Regras: mapa da onda 8, par. 4.
 */
@Component
@Profile("importacao")
class ImportacaoDoDjango {

	static final String ADMIN_DJANGO = "admin@clama.com.br";
	static final String ADMIN_NOVO = "admin@clama.me"; // decisao P3: mesmo hash, caixa que recebe
	/** Validade do Pix no clama-api ({@code Cobrancas.VALIDADE}); so decide se a cobranca importada esta vigente (nunca). */
	static final Duration VALIDADE_PIX = Duration.ofHours(24);

	private static final JsonMapper JSON = JsonMapper.builder()
			.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();

	private final JdbcClient jdbc;
	private final Cifra cifra;
	private final HmacDeIdentificador hmac;
	private final Auditoria auditoria;
	private final TransactionTemplate transacao;

	ImportacaoDoDjango(JdbcClient jdbc, Cifra cifra, HmacDeIdentificador hmac, Auditoria auditoria, TransactionTemplate transacao) {
		this.jdbc = jdbc;
		this.cifra = cifra;
		this.hmac = hmac;
		this.auditoria = auditoria;
		this.transacao = transacao;
	}

	// --- o JSON do extrator. toString sem PII (record imprimiria tudo).
	record Arquivo(String formato, String notas, List<UsuarioDj> usuarios, List<PlanoDj> planos, List<InstituicaoDj> instituicoes,
			List<PedidoDj> pedidos) {
		@Override public String toString() { return "Arquivo[...]"; }
	}

	record UsuarioDj(long id, String email, String password, String nomeCompleto, String name, String nomeFormatBlog,
			boolean isSuperuser, boolean isStaff, boolean isClamaAdmin, boolean isActive, boolean forceChangePassword,
			OffsetDateTime dateJoined, OffsetDateTime lastLogin) {
		@Override public String toString() { return "UsuarioDj[" + id + "]"; }
	}

	record PlanoDj(String id, String nome, String complexidade, int ordem, long valorCentavos, boolean ativo) {}

	record InstituicaoDj(String id, String nome, int ordem, boolean ativo) {}

	record PagamentoDj(String gateway, String pagamentoId, OffsetDateTime confirmadoEm) {}

	record PedidoDj(UUID id, Long userId, String planoId, String instituicaoId, String status, long valorCentavos,
			boolean ehGratuito, String nome, String email, String cpfCnpj, String telefone, Short idade, String sexo,
			String pedidoOracao, String oracaoGerada, OffsetDateTime consentAceitoAt, String consentIp, String consentVersao,
			int retryCount, OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime oracaoEmailSentAt,
			String providerPaymentId, String pixQrCode, String pixQrCodeBase64, PagamentoDj pagamento) {
		@Override public String toString() { return "PedidoDj[" + id + "]"; }
	}

	/** So contagens (vao para o terminal do operador). */
	record Resultado(Map<String, Object> contagens) {
		@Override public String toString() { return "Importacao do Django: " + contagens; }
	}

	Resultado importar(Path arquivo) {
		var a = JSON.readValue(arquivo.toFile(), Arquivo.class);
		if (!"clama-django-v1".equals(a.formato())) throw new IllegalStateException("formato desconhecido: " + a.formato());
		return transacao.execute(s -> importar(a));
	}

	private Resultado importar(Arquivo a) {
		var adminExistente = conferirDestinoVazio();
		var c = new TreeMap<String, Object>();
		var usuarios = importarUsuarios(a.usuarios(), adminExistente, c);
		var planos = planos(a);
		var instituicoes = instituicoes(a);
		var porStatus = new TreeMap<String, Integer>();
		int cobrancas = 0, cobrancasPagas = 0, bloqueios = 0;
		for (var p : a.pedidos()) {
			var status = status(p.status());
			var pagoEm = pagoEm(p, status);
			inserirPedido(p, status, usuarios, planos, instituicoes, pagoEm);
			porStatus.merge(status, 1, Integer::sum);
			var mp = mpPagamentoId(p);
			if (mp != null) {
				var pagaEm = p.ehGratuito() ? null : pagoEm;
				inserirCobranca(p, mp, pagaEm);
				cobrancas++;
				if (pagaEm != null) cobrancasPagas++;
			}
			auditoria.registrar(null, "pedido", p.id().toString(), Acao.ADICAO, trilha(p));
			if (bloqueia(p, status) && inserirBloqueio(p)) bloqueios++;
		}
		c.put("pedidos", a.pedidos().size());
		c.put("pedidos_por_status", porStatus);
		c.put("cobrancas", cobrancas);
		c.put("cobrancas_pagas", cobrancasPagas);
		c.put("freemium_bloqueios", bloqueios);
		auditoria.registrar(null, "importacao_django", "virada", Acao.ADICAO, Map.of("contagens", c));
		return new Resultado(c);
	}

	/** Destino novo: sem pedido, cobranca, repasse ou bloqueio; em usuario, no maximo o admin@clama.me do mapa (4.3.2). */
	private Long conferirDestinoVazio() {
		for (var t : List.of("pedido", "cobranca", "repasse", "freemium_bloqueio")) {
			if (jdbc.sql("select count(*) from " + t).query(Long.class).single() > 0)
				throw new IllegalStateException("destino nao esta vazio: " + t + " tem linhas");
		}
		var outros = jdbc.sql("select count(*) from usuario where email <> :admin or papel <> 'SUPERUSUARIO'")
				.param("admin", ADMIN_NOVO).query(Long.class).single();
		if (outros > 0) throw new IllegalStateException("destino nao esta vazio: usuario tem " + outros + " conta(s) alem do admin");
		return jdbc.sql("select id from usuario where email = :admin").param("admin", ADMIN_NOVO).query(Long.class).optional().orElse(null);
	}

	private Map<Long, Long> importarUsuarios(List<UsuarioDj> lista, Long adminExistente, Map<String, Object> c) {
		var ids = new HashMap<Long, Long>();
		int senhas = 0, semSenha = 0, superusuarios = 0;
		for (var u : lista) {
			var email = email(u.email());
			if (email.equals(ADMIN_NOVO) && adminExistente != null) {
				ids.put(u.id(), adminExistente); // criado antes pelo SQL do 4.3.2: fica como esta
				c.put("admin_reaproveitado", true);
				continue;
			}
			var senha = senha(u.password());
			if (senha == null) semSenha++; else senhas++;
			var papel = papel(u);
			if (papel.equals("SUPERUSUARIO")) superusuarios++;
			var nome = u.nomeCompleto() == null || u.nomeCompleto().isBlank() ? u.name() : u.nomeCompleto();
			var id = jdbc.sql("""
					insert into usuario (email, email_canonico, senha, nome_completo, nome_formato_blog, papel, ativo, criado_em, ultimo_login_em)
					values (:email, :canonico, :senha, :nome, :formato, :papel, :ativo, :criado, :login) returning id""")
					.param("email", email).param("canonico", HmacDeIdentificador.normalizarEmail(email)).param("senha", senha)
					.param("nome", nome == null ? "" : nome.strip().substring(0, Math.min(nome.strip().length(), 120)))
					.param("formato", "completo".equals(u.nomeFormatBlog()) ? "completo" : "compacto").param("papel", papel)
					.param("ativo", u.isActive()).param("criado", u.dateJoined()).param("login", u.lastLogin())
					.query(Long.class).single();
			ids.put(u.id(), id);
		}
		c.put("usuarios", ids.size());
		c.put("usuarios_com_senha", senhas);
		c.put("usuarios_sem_senha", semSenha);
		c.put("superusuarios", superusuarios);
		return ids;
	}

	/** Django id do plano -> id no destino: gratuito vai ao plano GRATUITO; o resto, pelo nome (ativo primeiro). */
	private Map<String, Long> planos(Arquivo a) {
		var ids = new HashMap<String, Long>();
		var faltando = new java.util.TreeSet<String>();
		var usados = a.pedidos().stream().filter(p -> !p.ehGratuito()).map(PedidoDj::planoId).collect(java.util.stream.Collectors.toSet());
		for (var p : a.planos()) {
			if (!usados.contains(p.id())) continue;
			jdbc.sql("select id from plano where nome = :nome order by ativo desc, id limit 1").param("nome", p.nome())
					.query(Long.class).optional().ifPresentOrElse(id -> ids.put(p.id(), id), () -> faltando.add(p.nome()));
		}
		if (!faltando.isEmpty()) throw new IllegalStateException("planos sem par no destino (cadastrar antes pelo admin): " + faltando);
		ids.put("GRATUITO", jdbc.sql("select id from plano where tipo = 'GRATUITO'").query(Long.class).single());
		return ids;
	}

	/** Django id da instituicao -> id no destino, pelo nome exato (ativa primeiro); so as que algum pedido usa. */
	private Map<String, Long> instituicoes(Arquivo a) {
		var ids = new HashMap<String, Long>();
		var faltando = new java.util.TreeSet<String>();
		var usadas = a.pedidos().stream().map(PedidoDj::instituicaoId).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
		for (var i : a.instituicoes()) {
			if (!usadas.contains(i.id())) continue;
			jdbc.sql("select id from instituicao where nome = :nome order by ativo desc, id limit 1").param("nome", i.nome())
					.query(Long.class).optional().ifPresentOrElse(id -> ids.put(i.id(), id), () -> faltando.add(i.nome()));
		}
		if (!faltando.isEmpty()) throw new IllegalStateException("instituicoes sem par no destino (cadastrar antes pelo admin): " + faltando);
		return ids;
	}

	private void inserirPedido(PedidoDj p, String status, Map<Long, Long> usuarios, Map<String, Long> planos,
			Map<String, Long> instituicoes, OffsetDateTime pagoEm) {
		var usuario = p.userId() == null ? null : usuarios.get(p.userId());
		if (p.userId() != null && usuario == null) throw new IllegalStateException("pedido " + p.id() + ": dono fora do arquivo");
		var cpf = vazioNulo(p.cpfCnpj());
		jdbc.sql("""
				insert into pedido (id, usuario_id, plano_id, instituicao_id, status, valor_centavos, gratuito, nome, email,
				  email_hmac, cpf_cnpj, cpf_hmac, telefone, idade, sexo, pedido_oracao, oracao, consentido_em, consentimento_ip,
				  consentimento_versao, ultimo_erro, tentativas, criado_em, atualizado_em, pago_em, enviada_em)
				values (:id, :usuario, :plano, :instituicao, :status, :valor, :gratuito, :nome, :email, :emailHmac, :cpf, :cpfHmac,
				  :telefone, :idade, :sexo, :pedidoOracao, :oracao, :consentidoEm, :ip, :versao, :erro, :tentativas, :criado,
				  :atualizado, :pagoEm, :enviadaEm)""")
				.param("id", p.id()).param("usuario", usuario)
				.param("plano", planos.get(p.ehGratuito() ? "GRATUITO" : p.planoId()))
				.param("instituicao", p.instituicaoId() == null ? null : instituicoes.get(p.instituicaoId()))
				.param("status", status).param("valor", p.ehGratuito() ? 0 : p.valorCentavos()).param("gratuito", p.ehGratuito())
				.param("nome", cifrar(p.nome())).param("email", cifrar(p.email().strip())).param("emailHmac", hmac.email(p.email()))
				.param("cpf", cifrar(cpf)).param("cpfHmac", cpf == null ? null : hmac.cpf(cpf))
				.param("telefone", cifrar(vazioNulo(p.telefone()))).param("idade", p.idade()).param("sexo", vazioNulo(p.sexo()))
				.param("pedidoOracao", cifrar(vazioNulo(p.pedidoOracao()))).param("oracao", cifrar(vazioNulo(p.oracaoGerada())))
				.param("consentidoEm", p.consentAceitoAt() == null ? p.createdAt() : p.consentAceitoAt())
				.param("ip", vazioNulo(p.consentIp())).param("versao", p.consentVersao() == null ? "" : p.consentVersao())
				.param("erro", ultimoErro(p.status(), status)).param("tentativas", p.retryCount())
				.param("criado", p.createdAt()).param("atualizado", p.updatedAt()).param("pagoEm", pagoEm)
				.param("enviadaEm", status.equals("ENVIADA") ? (p.oracaoEmailSentAt() != null ? p.oracaoEmailSentAt() : p.updatedAt()) : null)
				.update();
	}

	private void inserirCobranca(PedidoDj p, long mp, OffsetDateTime pagaEm) {
		jdbc.sql("""
				insert into cobranca (pedido_id, n, mp_pagamento_id, pix_copia_e_cola, pix_qr_base64, expira_em, criada_em, paga_em)
				values (:pedido, 1, :mp, :pix, :qr, :expira, :criada, :paga)""")
				.param("pedido", p.id()).param("mp", mp).param("pix", p.pixQrCode() == null ? "" : p.pixQrCode())
				.param("qr", p.pixQrCodeBase64()).param("expira", p.createdAt().plus(VALIDADE_PIX)).param("criada", p.createdAt())
				.param("paga", pagaEm).update();
	}

	/** Como {@code Bloqueios.inserir}: on conflict do nothing (e-mail ou CPF ja bloqueado). Sem IP: a janela e de 1 h. */
	private boolean inserirBloqueio(PedidoDj p) {
		return jdbc.sql("""
				insert into freemium_bloqueio (email_hmac, cpf_hmac, ip_hmac, criado_em) values (:e, :c, null, :em)
				on conflict do nothing""")
				.param("e", hmac.email(p.email())).param("c", hmac.cpf(p.cpfCnpj())).param("em", p.createdAt()).update() == 1;
	}

	// --- regras (mapa da onda 8, par. 4.2)

	/** Pedido presos (pago, gerando, gerada, aguardando reenvio) viram ERRO, decisao do Pedro; o admin reenvia ou regera. */
	static String status(String django) {
		return switch (django) {
			case "enviada" -> "ENVIADA";
			case "aguardando_pagamento" -> "AGUARDANDO_PAGAMENTO"; // Pix vencido: o doador pode gerar outro; nada terminal mente menos
			case "aguardando_confirmacao_email" -> "CONFIRMACAO_EXPIRADA"; // o token nao vem: o link morreu
			case "pago", "gerando_oracao", "oracao_gerada", "aguardando_reenvio", "erro" -> "ERRO";
			default -> throw new IllegalStateException("status do Django desconhecido: " + django);
		};
	}

	static String ultimoErro(String django, String status) {
		if (!status.equals("ERRO")) return null;
		return django.equals("erro") ? "erro_no_django" : "pedido_preso";
	}

	/** Passou do pagamento (ou da gratuidade): a hora do evento PROCESSADO do gateway, senao a criacao (aproximado). */
	static OffsetDateTime pagoEm(PedidoDj p, String status) {
		if (status.equals("AGUARDANDO_PAGAMENTO") || status.equals("CONFIRMACAO_EXPIRADA")) return null;
		return p.pagamento() != null && p.pagamento().confirmadoEm() != null ? p.pagamento().confirmadoEm() : p.createdAt();
	}

	/** Id do pagamento no MP (numerico): o do pedido ou, sem ele, o do evento PROCESSADO. Asaas nao vira cobranca. */
	static Long mpPagamentoId(PedidoDj p) {
		var id = numero(p.providerPaymentId());
		if (id == null && p.pagamento() != null && "MERCADO_PAGO".equals(p.pagamento().gateway())) id = numero(p.pagamento().pagamentoId());
		return id;
	}

	/** Quem recebeu a oracao gratuita fica bloqueado (e-mail e CPF com o HMAC novo). */
	static boolean bloqueia(PedidoDj p, String status) {
		return p.ehGratuito() && !status.equals("CONFIRMACAO_EXPIRADA") && vazioNulo(p.cpfCnpj()) != null;
	}

	/** {@code argon2$argon2id$...} vira {@code {argon2}$argon2id$...} (ConversaoDeSenhaDjangoTest); outro formato fica sem senha. */
	static String senha(String django) {
		return django != null && django.startsWith("argon2$argon2id$") ? "{argon2}" + django.substring("argon2".length()) : null;
	}

	static String papel(UsuarioDj u) {
		return u.isSuperuser() || u.isStaff() || u.isClamaAdmin() ? "SUPERUSUARIO" : "CLIENTE";
	}

	static String email(String django) {
		var e = django.strip().toLowerCase(java.util.Locale.ROOT);
		return e.equals(ADMIN_DJANGO) ? ADMIN_NOVO : e;
	}

	private static Map<String, Object> trilha(PedidoDj p) {
		var d = new LinkedHashMap<String, Object>();
		d.put("origem", "django");
		d.put("status_django", p.status());
		if (p.pagamento() != null) {
			d.put("gateway", p.pagamento().gateway());
			d.put("pagamento_id", p.pagamento().pagamentoId());
		} else if (p.providerPaymentId() != null) {
			d.put("pagamento_id", p.providerPaymentId());
		}
		return d;
	}

	private String cifrar(String claro) {
		return claro == null ? null : cifra.cifrar(claro);
	}

	private static String vazioNulo(String s) {
		return s == null || s.isBlank() ? null : s;
	}

	private static Long numero(String s) {
		return s != null && s.matches("\\d{1,18}") ? Long.valueOf(s) : null;
	}
}
