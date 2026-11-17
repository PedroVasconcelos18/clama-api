package me.clama.conta;

import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Optional;
import java.util.Map;
import java.util.stream.Collectors;
import me.clama.conta.interno.AdminDeUsuariosServico;
import me.clama.conta.interno.ContaProps;
import me.clama.conta.interno.EmailCanonico;
import me.clama.conta.interno.NomePublico;
import me.clama.conta.interno.Papel;
import me.clama.conta.interno.TokensDeSenha;
import me.clama.conta.interno.Usuario;
import me.clama.conta.interno.UsuarioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Interface do modulo para os outros (doacao, oracao): achar ou criar a conta de quem doa. */
@Service
public class ContaApi {

	private final UsuarioRepository usuarios;
	private final Clock relogio;
	private final AdminDeUsuariosServico admin;
	private final TokensDeSenha tokens;
	private final ContaProps props;

	ContaApi(UsuarioRepository usuarios, Clock relogio, AdminDeUsuariosServico admin, TokensDeSenha tokens, ContaProps props) {
		this.admin = admin;
		this.usuarios = usuarios;
		this.relogio = relogio;
		this.tokens = tokens;
		this.props = props;
	}

	/** Ha conta com esse e-mail (comparado pelo canonico: Gmail sem pontos e sem "+tag"). */
	@Transactional(readOnly = true)
	public boolean existe(String email) {
		return usuarios.findByEmailCanonico(EmailCanonico.de(email)).isPresent();
	}

	/**
	 * Conta existente pelo e-mail canonico: so devolve. Senao cria um CLIENTE sem senha, sem e-mail nenhum: o link de
	 * ativacao vai no e-mail da oracao ({@link #linkDeAtivacao}). Criacao simultanea do mesmo e-mail: o
	 * {@code ON CONFLICT DO NOTHING} espera o outro commit e solta sem erro (sem excecao, a transacao do chamador nao se perde), e a conta e relida.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public UsuarioResumo provisionarOuVincular(String email, String nomeCompleto) {
		var canonico = EmailCanonico.de(email);
		var existente = usuarios.findByEmailCanonico(canonico);
		if (existente.isEmpty()) {
			var nome = nomeCompleto == null ? "" : nomeCompleto.strip();
			usuarios.inserirSeAusente(email.strip().toLowerCase(Locale.ROOT), canonico,
					nome.length() > 120 ? nome.substring(0, 120) : nome, relogio.instant());
			existente = usuarios.findByEmailCanonico(canonico);
		}
		var u = existente.orElseThrow();
		return resumo(u);
	}

	private static UsuarioResumo resumo(Usuario u) {
		return new UsuarioResumo(u.getId(), u.getEmail(), u.getNomeCompleto(), NomePublico.de(u), u.getPapel() != Papel.CLIENTE, u.getPapel().name());
	}

	/**
	 * Token cru de definir senha (72 h) para a conta ativa ainda sem senha; vazio se tem senha, esta inativa ou nao existe.
	 * Nao revoga os anteriores: o da tela sobrevive ao do e-mail. Credencial: nunca logar. Transacao propria.
	 */
	public Optional<String> tokenDeAtivacao(Long usuarioId) {
		return usuarios.findById(usuarioId).filter(u -> u.isAtivo() && !u.temSenha())
				.map(u -> tokens.emitirSemRevogar(u.getId(), TokensDeSenha.Finalidade.DEFINIR));
	}

	/** {@code <frontend>/definir-senha#token=...} com um {@link #tokenDeAtivacao} novo; mesmas regras. */
	public Optional<String> linkDeAtivacao(Long usuarioId) {
		return tokenDeAtivacao(usuarioId).map(t -> props.linkDeSenha("definir-senha", t));
	}

	/** Uma consulta so; id inexistente fica fora do mapa. */
	@Transactional(readOnly = true)
	public Map<Long, UsuarioResumo> resumos(Collection<Long> ids) {
		if (ids.isEmpty()) return Map.of();
		return usuarios.findAllById(ids).stream().collect(Collectors.toMap(Usuario::getId, ContaApi::resumo));
	}

	/** A conta desse e-mail, comparado pelo canonico (Gmail sem pontos e sem "+tag"). */
	@Transactional(readOnly = true)
	public Optional<UsuarioResumo> contaPorEmail(String email) {
		return usuarios.findByEmailCanonico(EmailCanonico.de(email)).map(ContaApi::resumo);
	}

	/** Tudo o que a conta guarda da pessoa (LGPD), sem a senha nem hash algum. Datas em {@code Instant}. */
	@Transactional(readOnly = true)
	public Map<String, Object> exportar(Long id) {
		var u = usuarios.findById(id).orElseThrow();
		var m = new LinkedHashMap<String, Object>();
		m.put("id", u.getId());
		m.put("email", u.getEmail());
		m.put("nomeCompleto", u.getNomeCompleto());
		m.put("nomeFormatoBlog", u.getNomeFormatoBlog());
		m.put("papel", u.getPapel().name());
		m.put("ativo", u.isAtivo());
		m.put("criadoEm", u.getCriadoEm());
		m.put("ultimoLoginEm", u.getUltimoLoginEm());
		return m;
	}

	/**
	 * Exclui a conta com as guardas da administracao de usuarios (nao a propria, nao o ultimo superusuario: 409) e a
	 * auditoria; as FKs levam token de senha, comentarios, curtidas e ban recebido, e as sessoes caem. Na transacao
	 * do chamador (a LGPD).
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void excluir(Long autorId, Long id) {
		admin.excluir(autorId, id);
	}
}
