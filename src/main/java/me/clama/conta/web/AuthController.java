package me.clama.conta.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import me.clama.conta.LoginServico;
import me.clama.conta.Usuario;
import me.clama.conta.UsuarioRepository;
import me.clama.infra.seguranca.ClienteIp;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Login por JSON feito a mao (sem UsernamePasswordAuthenticationFilter); a sessao mora no Spring Session JDBC. */
@RestController
@RequestMapping("/api/auth")
class AuthController {

	record Credenciais(@NotBlank String email, @NotBlank String senha) {}

	private final LoginServico login;
	private final UsuarioRepository usuarios;
	private final SessionAuthenticationStrategy estrategiaDeSessao;
	private final SecurityContextRepository contextos = new HttpSessionSecurityContextRepository();
	private final SecurityContextHolderStrategy contextoAtual = SecurityContextHolder.getContextHolderStrategy();

	AuthController(LoginServico login, UsuarioRepository usuarios, CsrfTokenRepository csrf) {
		this.login = login;
		this.usuarios = usuarios;
		// novo id de sessao (fixacao) e novo token CSRF (o anonimo nao vale para a sessao autenticada)
		this.estrategiaDeSessao = new CompositeSessionAuthenticationStrategy(
				List.of(new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(csrf)));
	}

	@PostMapping("/login")
	UsuarioResposta login(@Valid @RequestBody Credenciais credenciais, HttpServletRequest req,
			HttpServletResponse res) {
		var usuario = login.autenticar(credenciais.email(), credenciais.senha(), ClienteIp.de(req));
		var id = usuario.getId().toString(); // principal = id: o indice que derruba as sessoes do usuario
		var auth = UsernamePasswordAuthenticationToken.authenticated(id, null,
				List.of(new SimpleGrantedAuthority("ROLE_" + usuario.getPapel())));

		estrategiaDeSessao.onAuthentication(auth, req, res);
		var contexto = contextoAtual.createEmptyContext();
		contexto.setAuthentication(auth);
		contextoAtual.setContext(contexto);
		contextos.saveContext(contexto, req, res);
		req.getSession().setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, id);
		if (req.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken novo) {
			novo.getToken(); // token carregado sob demanda: ler forca o Set-Cookie XSRF-TOKEN nesta resposta
		}
		return UsuarioResposta.de(usuario);
	}

	/** Idempotente: sem sessao tambem da 204. Invalidar a sessao apaga a linha do SPRING_SESSION. */
	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void logout(HttpServletRequest req, HttpServletResponse res) {
		new SecurityContextLogoutHandler().logout(req, res, contextoAtual.getContext().getAuthentication());
	}

	/**
	 * Le o usuario do banco: conta apagada ou inativa da 401 aqui. As demais rotas confiam na sessao; desativar
	 * derruba as sessoes (Task 5), entao nao ha consulta ao banco por requisicao.
	 */
	@GetMapping("/eu")
	UsuarioResposta eu(Authentication auth) {
		return usuarios.findById(Long.valueOf(auth.getName())).filter(Usuario::isAtivo).map(UsuarioResposta::de)
				.orElseThrow(() -> new InsufficientAuthenticationException("Usuario da sessao inexistente ou inativo"));
	}
}
