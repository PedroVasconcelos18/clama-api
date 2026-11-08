package me.clama.conta.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import me.clama.conta.TrocaDeSenhaServico;
import me.clama.conta.UsuarioRepository;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Minha conta: so nome e formato do nome no blog (e-mail e papel nunca vem do cliente) e troca de senha. */
@RestController
@RequestMapping("/api/conta")
class ContaController {

	record Edicao(@Size(max = 120) String nomeCompleto,
			@Pattern(regexp = "compacto|completo", message = "Use compacto ou completo.") String nomeFormatoBlog) {}

	record Troca(@NotBlank @Size(max = 1024) String senhaAtual, @NotBlank @Size(max = 1024) String novaSenha) {}

	private final UsuarioRepository usuarios;
	private final TransactionTemplate transacao;
	private final TrocaDeSenhaServico troca;

	ContaController(UsuarioRepository usuarios, TransactionTemplate transacao, TrocaDeSenhaServico troca) {
		this.usuarios = usuarios;
		this.transacao = transacao;
		this.troca = troca;
	}

	@PatchMapping("/eu")
	UsuarioResposta editar(@Valid @RequestBody Edicao edicao, Authentication auth) {
		return transacao.execute(s -> {
			var u = usuarios.findById(Long.valueOf(auth.getName())).orElseThrow();
			if (edicao.nomeCompleto() != null) u.renomear(edicao.nomeCompleto().strip());
			if (edicao.nomeFormatoBlog() != null) u.escolherFormatoBlog(edicao.nomeFormatoBlog());
			return UsuarioResposta.de(u);
		});
	}

	@PostMapping("/senha")
	Map<String, String> trocarSenha(@Valid @RequestBody Troca corpo, Authentication auth, HttpServletRequest req) {
		troca.trocar(Long.valueOf(auth.getName()), corpo.senhaAtual(), corpo.novaSenha(), req.getSession().getId());
		return Map.of("mensagemPastoral", TrocaDeSenhaServico.PASTORAL);
	}
}
