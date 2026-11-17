package me.clama.conta.interno;

import java.util.Map;
import me.clama.infra.email.EnviadorDeEmail;
import me.clama.infra.email.ModelosDeEmail;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/** Os dois e-mails de link de senha: redefinir ("esqueci" e cadastro de e-mail que ja tem conta) e definir (cadastro novo). */
@Component
@EnableConfigurationProperties(ContaProps.class)
class EmailsDaConta {

	private final ModelosDeEmail modelos;
	private final EnviadorDeEmail enviador;
	private final ContaProps props;

	EmailsDaConta(ModelosDeEmail modelos, EnviadorDeEmail enviador, ContaProps props) {
		this.modelos = modelos;
		this.enviador = enviador;
		this.props = props;
	}

	void enviarRedefinicao(Usuario usuario, String token) {
		enviar(usuario, token, "redefinir-senha", "Redefina sua senha — Clama", "redefinicao_senha");
	}

	void enviarDefinicao(Usuario usuario, String token) {
		enviar(usuario, token, "definir-senha", "Bem-vinda ao Clama — defina sua senha", "definir_senha");
	}

	private void enviar(Usuario usuario, String token, String rota, String assunto, String modelo) {
		var primeiro = usuario.getNomeCompleto().isBlank() ? "Amada" : usuario.getNomeCompleto().strip().split("\\s+")[0];
		var email = modelos.montar(usuario.getEmail(), assunto, modelo,
				Map.of("primeiro_nome", primeiro, "link", props.linkDeSenha(rota, token)));
		enviador.enviar(email, "senha/" + TokensDeSenha.hash(token)); // chave estavel por token: retentativa nao duplica
	}
}
