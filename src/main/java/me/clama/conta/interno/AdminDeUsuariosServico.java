package me.clama.conta.interno;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.clama.conta.interno.senha.ValidadorDeSenha;
import me.clama.infra.auditoria.Acao;
import me.clama.infra.auditoria.Auditoria;
import me.clama.infra.web.ErroDeCampo;
import me.clama.infra.web.ErroDeNegocio;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Administracao de usuarios (tela do admin). Toda escrita e uma transacao curta que audita; o hash da senha roda antes,
 * fora dela (VagasDeHash). As sessoes so caem depois do commit, para nao derrubar quem teve a escrita desfeita. Toda
 * escrita que pode tirar um superusuario ativo toma o lock de admins antes de contar, para dois pedidos simultaneos
 * nao deixarem o sistema sem nenhum.
 */
@Service
public class AdminDeUsuariosServico {

	private static final String PASTORAL_NAO_ENCONTRADO = "Esse usuário não foi encontrado.";

	private final UsuarioRepository usuarios;
	private final Auditoria auditoria;
	private final JdbcClient jdbc;
	private final TransactionTemplate transacao;
	private final TokensDeSenha tokens;
	private final SessoesDoUsuario sessoes;
	private final PasswordEncoder senhas;
	private final ValidadorDeSenha validador;
	private final VagasDeHash vagas;
	private final Clock relogio;

	AdminDeUsuariosServico(UsuarioRepository usuarios, Auditoria auditoria, JdbcClient jdbc,
			TransactionTemplate transacao, TokensDeSenha tokens, SessoesDoUsuario sessoes, PasswordEncoder senhas,
			ValidadorDeSenha validador, VagasDeHash vagas, Clock relogio) {
		this.usuarios = usuarios;
		this.auditoria = auditoria;
		this.jdbc = jdbc;
		this.transacao = transacao;
		this.tokens = tokens;
		this.sessoes = sessoes;
		this.senhas = senhas;
		this.validador = validador;
		this.vagas = vagas;
		this.relogio = relogio;
	}

	// --- leitura ---

	public Page<Usuario> listar(String q, Papel papel, Boolean ativo, Pageable pagina) {
		Specification<Usuario> filtro = (r, c, cb) -> cb.conjunction();
		if (q != null && !q.isBlank()) {
			var padrao = "%" + q.strip().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%")
					.replace("_", "\\_") + "%";
			filtro = filtro.and((r, c, cb) -> cb.or(cb.like(cb.lower(r.get("email")), padrao, '\\'),
					cb.like(cb.lower(r.get("nomeCompleto")), padrao, '\\')));
		}
		if (papel != null) filtro = filtro.and((r, c, cb) -> cb.equal(r.get("papel"), papel));
		if (ativo != null) filtro = filtro.and((r, c, cb) -> cb.equal(r.get("ativo"), ativo));
		return usuarios.findAll(filtro, pagina);
	}

	public Usuario detalhar(Long id) {
		return usuarios.findById(id).orElseThrow(AdminDeUsuariosServico::naoEncontrado);
	}

	// --- escrita ---

	/**
	 * Cria um ADMIN. Usa o insert com ON CONFLICT do repositorio em vez de checar e inserir: a violacao de unicidade num
	 * insert comum deixa a transacao do Postgres abortada, e a checagem previa sozinha perde a corrida. 0 linhas = o
	 * e-mail (ou o canonico: Gmail com ponto ou +tag) ja e de alguem.
	 */
	public Usuario criar(Long autorId, String email, String nomeCompleto, String senha) {
		var minusculo = email.strip().toLowerCase(Locale.ROOT);
		validador.exigirForte(senha, minusculo, "senha");
		if (usuarios.findByEmailCanonico(EmailCanonico.de(minusculo)).isPresent()) throw emailDuplicado();
		var hash = vagas.comVaga(() -> senhas.encode(senha));
		var nome = nomeCompleto == null ? "" : nomeCompleto.strip();
		return transacao.execute(s -> {
			if (usuarios.inserirSeAusente(minusculo, EmailCanonico.de(minusculo), nome, relogio.instant()) == 0) {
				throw emailDuplicado();
			}
			var u = usuarios.findByEmailCanonico(EmailCanonico.de(minusculo)).orElseThrow();
			u.promover(Papel.ADMIN);
			u.definirSenha(hash);
			auditoria.registrar(autorId, "usuario", u.getId().toString(), Acao.ADICAO,
					Map.of("papel", mudanca(null, Papel.ADMIN)));
			return u;
		});
	}

	/** Muda papel e/ou ativo (nulo = deixa). Sem diferenca, nada e gravado nem auditado. */
	public Usuario alterar(Long autorId, Long id, Papel novoPapel, Boolean novoAtivo) {
		var derrubar = new boolean[1];
		var resultado = transacao.execute(s -> {
			if (id.equals(autorId)) {
				throw conflito("nao_pode_alterar_a_si_mesmo", "Não é possível alterar a própria conta.",
						"Você não pode alterar a sua própria conta por aqui.");
			}
			travarAdmins();
			var u = usuarios.travar(id).orElseThrow(AdminDeUsuariosServico::naoEncontrado);
			var papelAntes = u.getPapel();
			var ativoAntes = u.isAtivo();
			var papelDepois = novoPapel != null ? novoPapel : papelAntes;
			var ativoDepois = novoAtivo != null ? novoAtivo : ativoAntes;
			var diff = new LinkedHashMap<String, Object>();
			if (papelDepois != papelAntes) diff.put("papel", mudanca(papelAntes, papelDepois));
			if (ativoDepois != ativoAntes) diff.put("ativo", mudanca(ativoAntes, ativoDepois));
			if (diff.isEmpty()) return u;

			if (papelAntes == Papel.SUPERUSUARIO && ativoAntes && !(papelDepois == Papel.SUPERUSUARIO && ativoDepois)) {
				exigirOutroSuperusuario();
			}
			u.promover(papelDepois);
			if (ativoDepois) u.reativar(); else u.desativar();
			if (ativoAntes && !ativoDepois) tokens.revogarTodos(id); // conta desativada nao recebe link pendente
			derrubar[0] = (ativoAntes && !ativoDepois) || papelDepois != papelAntes;
			auditoria.registrar(autorId, "usuario", id.toString(), Acao.MUDANCA, diff);
			return u;
		});
		if (derrubar[0]) sessoes.derrubarTodas(id); // o papel fica congelado na sessao: sem isto quem mudou de papel segue com o antigo
		return resultado;
	}

	public void definirSenha(Long autorId, Long id, String senha) {
		var alvo = detalhar(id);
		validador.exigirForte(senha, alvo.getEmail(), "senha");
		var hash = vagas.comVaga(() -> senhas.encode(senha));
		transacao.executeWithoutResult(s -> {
			var u = usuarios.travar(id).orElseThrow(AdminDeUsuariosServico::naoEncontrado);
			u.definirSenha(hash);
			tokens.revogarTodos(id);
			auditoria.registrar(autorId, "usuario", id.toString(), Acao.MUDANCA, Map.of("senha", "alterada"));
		});
		sessoes.derrubarTodas(id);
	}

	/** Apaga o usuario. O resto cai pelas FKs: token_de_senha, comentarios, curtidas e ban recebido em cascade; "banido por" vira nulo (V7, D10). Sem guarda do blog. */
	public void excluir(Long autorId, Long id) {
		transacao.executeWithoutResult(s -> {
			if (id.equals(autorId)) {
				throw conflito("nao_pode_excluir_a_si_mesmo", "Não é possível excluir a própria conta.",
						"Você não pode excluir a sua própria conta.");
			}
			travarAdmins();
			var u = usuarios.travar(id).orElseThrow(AdminDeUsuariosServico::naoEncontrado);
			if (u.getPapel() == Papel.SUPERUSUARIO && u.isAtivo()) exigirOutroSuperusuario();
			auditoria.registrar(autorId, "usuario", id.toString(), Acao.EXCLUSAO,
					Map.of("papel", mudanca(u.getPapel(), null)));
			usuarios.delete(u);
			usuarios.flush();
		});
		sessoes.derrubarTodas(id);
	}

	// --- apoio ---

	/** Serializa as escritas que podem reduzir os superusuarios; tomar antes de contar, na transacao. */
	private void travarAdmins() {
		jdbc.sql("select pg_advisory_xact_lock(hashtext('admins'))").query((rs, n) -> 1).single();
	}

	/** O alvo conta nos ativos: precisa haver pelo menos outro. */
	private void exigirOutroSuperusuario() {
		if (usuarios.countByPapelAndAtivoTrue(Papel.SUPERUSUARIO) < 2) {
			throw conflito("ultimo_superusuario", "Último superusuário ativo.",
					"Essa é a última conta de superusuário ativa. Torne outra conta superusuária antes.");
		}
	}

	static Map<String, Object> mudanca(Object antes, Object depois) {
		var m = new LinkedHashMap<String, Object>();
		m.put("antes", antes instanceof Enum<?> e ? e.name() : antes);
		m.put("depois", depois instanceof Enum<?> e ? e.name() : depois);
		return m;
	}

	private static ErroDeNegocio emailDuplicado() {
		return new ErroDeNegocio(HttpStatus.BAD_REQUEST, "dados_invalidos", "Dados inválidos.",
				"Já existe uma conta com este e-mail.", List.of(new ErroDeCampo("email", "Usuário com este E-mail já existe.")));
	}

	private static ErroDeNegocio conflito(String codigo, String mensagem, String pastoral) {
		return new ErroDeNegocio(HttpStatus.CONFLICT, codigo, mensagem, pastoral);
	}

	private static ErroDeNegocio naoEncontrado() {
		return new ErroDeNegocio(HttpStatus.NOT_FOUND, "nao_encontrado", PASTORAL_NAO_ENCONTRADO, PASTORAL_NAO_ENCONTRADO);
	}
}
