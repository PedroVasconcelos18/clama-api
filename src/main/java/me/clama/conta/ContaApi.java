package me.clama.conta;

import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Optional;
import java.util.Map;
import java.util.stream.Collectors;
import me.clama.conta.interno.AdminDeUsuariosServico;
import me.clama.conta.interno.ContaCriada;
import me.clama.conta.interno.EmailCanonico;
import me.clama.conta.interno.NomePublico;
import me.clama.conta.interno.Papel;
import me.clama.conta.interno.Usuario;
import me.clama.conta.interno.UsuarioRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Interface do modulo para os outros (doacao, oracao): achar ou criar a conta de quem doa. */
@Service
public class ContaApi {

	private final UsuarioRepository usuarios;
	private final ApplicationEventPublisher eventos;
	private final Clock relogio;
	private final AdminDeUsuariosServico admin;

	ContaApi(UsuarioRepository usuarios, ApplicationEventPublisher eventos, Clock relogio, AdminDeUsuariosServico admin) {
		this.admin = admin;
		this.usuarios = usuarios;
		this.eventos = eventos;
		this.relogio = relogio;
	}

	/** Ha conta com esse e-mail (comparado pelo canonico: Gmail sem pontos e sem "+tag"). */
	@Transactional(readOnly = true)
	public boolean existe(String email) {
		return usuarios.findByEmailCanonico(EmailCanonico.de(email)).isPresent();
	}

	/**
	 * Conta existente pelo e-mail canonico: so devolve. Senao cria um CLIENTE sem senha e, depois do commit de quem
	 * chamou, manda o link de definir senha. Criacao simultanea do mesmo e-mail: o {@code ON CONFLICT DO NOTHING}
	 * espera o outro commit e solta sem erro (sem excecao, a transacao do chamador nao se perde), e a conta e relida.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public UsuarioResumo provisionarOuVincular(String email, String nomeCompleto) {
		var canonico = EmailCanonico.de(email);
		var existente = usuarios.findByEmailCanonico(canonico);
		if (existente.isEmpty()) {
			var nome = nomeCompleto == null ? "" : nomeCompleto.strip();
			var criou = usuarios.inserirSeAusente(email.strip().toLowerCase(Locale.ROOT), canonico,
					nome.length() > 120 ? nome.substring(0, 120) : nome, relogio.instant()) == 1;
			existente = usuarios.findByEmailCanonico(canonico);
			if (criou) eventos.publishEvent(new ContaCriada(existente.orElseThrow().getId()));
		}
		var u = existente.orElseThrow();
		return resumo(u);
	}

	private static UsuarioResumo resumo(Usuario u) {
		return new UsuarioResumo(u.getId(), u.getEmail(), u.getNomeCompleto(), NomePublico.de(u), u.getPapel() != Papel.CLIENTE, u.getPapel().name());
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
