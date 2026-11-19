package me.clama.conta.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import me.clama.conta.interno.Cadastro;
import me.clama.infra.antibot.Turnstile;
import me.clama.infra.seguranca.ClienteIp;
import me.clama.infra.validacao.EmailNaoDescartavelValidator;
import me.clama.infra.web.ErroDeCampo;
import me.clama.infra.web.ErroDeNegocio;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Anonimo (com CSRF). Ordem: campos (@Valid), Turnstile, e-mail descartavel e o {@link Cadastro} (limite e envio). */
@RestController
class CadastroController {

	static final String CAPTCHA = "Verificação anti-robô falhou. Atualize a página e tente de novo.";
	static final String DESCARTAVEL = "Use um e-mail principal para criar sua conta — e-mails temporários não funcionam aqui.";

	record PedidoDeCadastro(@NotBlank @Size(max = 120) String nome, @NotBlank @Email @Size(max = 254) String email,
			@Size(max = 2048) String turnstileToken) {}

	private final Cadastro cadastro;
	private final Turnstile turnstile;

	CadastroController(Cadastro cadastro, Turnstile turnstile) {
		this.cadastro = cadastro;
		this.turnstile = turnstile;
	}

	@PostMapping("/api/auth/cadastro")
	MensagemPastoral cadastrar(@Valid @RequestBody PedidoDeCadastro corpo, HttpServletRequest req) {
		var ip = ClienteIp.de(req);
		if (corpo.turnstileToken() == null || corpo.turnstileToken().isBlank()) {
			throw new ErroDeNegocio(HttpStatus.BAD_REQUEST, "dados_invalidos", "Dados inválidos.", null,
					List.of(new ErroDeCampo("turnstileToken", CAPTCHA)));
		}
		if (!turnstile.verificar(corpo.turnstileToken(), ip)) {
			throw new ErroDeNegocio(HttpStatus.BAD_REQUEST, "captcha_invalido", "Token Turnstile inválido", CAPTCHA);
		}
		if (!new EmailNaoDescartavelValidator().isValid(corpo.email(), null)) {
			throw new ErroDeNegocio(HttpStatus.BAD_REQUEST, "email_descartavel", "E-mail descartável não permitido", DESCARTAVEL);
		}
		cadastro.cadastrar(corpo.nome(), corpo.email(), ip);
		return new MensagemPastoral(Cadastro.PASTORAL);
	}
}
