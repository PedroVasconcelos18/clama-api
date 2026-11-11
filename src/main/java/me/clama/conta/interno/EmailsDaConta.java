package me.clama.conta.interno;

import java.util.Map;
import me.clama.infra.email.EnvioAssincrono;
import me.clama.infra.email.EnviadorDeEmail;
import me.clama.infra.email.ModelosDeEmail;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Os dois e-mails de link de senha; o de "definir" sai depois do commit da conta nova. */
@Component
@EnableConfigurationProperties(ContaProps.class)
class EmailsDaConta {

	private final ModelosDeEmail modelos;
	private final EnviadorDeEmail enviador;
	private final ContaProps props;
	private final UsuarioRepository usuarios;
	private final TokensDeSenha tokens;
	private final EnvioAssincrono envio;

	EmailsDaConta(ModelosDeEmail modelos, EnviadorDeEmail enviador, ContaProps props, UsuarioRepository usuarios,
			TokensDeSenha tokens, EnvioAssincrono envio) {
		this.envio = envio;
		this.modelos = modelos;
		this.enviador = enviador;
		this.props = props;
		this.usuarios = usuarios;
		this.tokens = tokens;
	}

	void enviarRedefinicao(Usuario usuario, String token) {
		enviar(usuario, token, "redefinir-senha", "Redefina sua senha — Clama", "redefinicao_senha");
	}

	private void enviar(Usuario usuario, String token, String rota, String assunto, String modelo) {
		var base = props.frontendUrl().replaceAll("/+$", "");
		var primeiro = usuario.getNomeCompleto().isBlank() ? "Amada" : usuario.getNomeCompleto().strip().split("\\s+")[0];
		var email = modelos.montar(usuario.getEmail(), assunto, modelo,
				Map.of("primeiro_nome", primeiro, "link", base + "/" + rota + "#token=" + token));
		enviador.enviar(email, "senha/" + TokensDeSenha.hash(token)); // chave estavel por token: retentativa nao duplica
	}

	/** Falha de envio nao desfaz a conta (ja commitada): vai para o log e a pessoa pede o link em "esqueci a senha". */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void aoCriarConta(ContaCriada evento) {
		envio.executar("link de definir senha da conta " + evento.id(), () -> {
			var usuario = usuarios.findById(evento.id()).orElseThrow();
			var token = tokens.emitir(usuario.getId(), TokensDeSenha.Finalidade.DEFINIR);
			enviar(usuario, token, "definir-senha", "Bem-vinda ao Clama — defina sua senha", "definir_senha");
		});
	}
}
