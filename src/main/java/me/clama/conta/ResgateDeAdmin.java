package me.clama.conta;

import java.util.LinkedHashMap;
import me.clama.infra.auditoria.Acao;
import me.clama.infra.auditoria.Auditoria;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ninguem consegue entrar? Subir com CLAMA_ADMIN_RESGATE_EMAIL promove essa conta a SUPERUSUARIO e a reativa. Nao define
 * senha (o acesso volta pela redefinicao). Conta inexistente derruba o boot: silencio aqui esconderia um e-mail errado.
 * Idempotente: sem mudanca, sem linha de auditoria. O log mostra so o dominio.
 */
@Component
class ResgateDeAdmin implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(ResgateDeAdmin.class);

	private final ContaProps props;
	private final UsuarioRepository usuarios;
	private final Auditoria auditoria;
	private final TransactionTemplate transacao;

	ResgateDeAdmin(ContaProps props, UsuarioRepository usuarios, Auditoria auditoria, TransactionTemplate transacao) {
		this.props = props;
		this.usuarios = usuarios;
		this.auditoria = auditoria;
		this.transacao = transacao;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (props.adminResgateEmail() == null || props.adminResgateEmail().isBlank()) return;
		resgatar(props.adminResgateEmail());
	}

	void resgatar(String email) {
		var dominio = email.contains("@") ? email.substring(email.lastIndexOf('@') + 1).strip() : "?";
		transacao.executeWithoutResult(s -> {
			var u = usuarios.findByEmailCanonico(EmailCanonico.de(email)).orElseThrow(() -> new IllegalStateException(
					"CLAMA_ADMIN_RESGATE_EMAIL aponta para uma conta que nao existe (dominio: " + dominio + ")"));
			var diff = new LinkedHashMap<String, Object>();
			if (u.getPapel() != Papel.SUPERUSUARIO) {
				diff.put("papel", AdminDeUsuariosServico.mudanca(u.getPapel(), Papel.SUPERUSUARIO));
				u.promover(Papel.SUPERUSUARIO);
			}
			if (!u.isAtivo()) {
				diff.put("ativo", AdminDeUsuariosServico.mudanca(false, true));
				u.reativar();
			}
			if (diff.isEmpty()) {
				log.info("Resgate de admin: a conta ({}) ja e superusuaria e ativa", dominio);
				return;
			}
			auditoria.registrar(null, "usuario", u.getId().toString(), Acao.RESGATE, diff);
			log.warn("Resgate de admin: conta ({}) promovida a superusuario e reativada", dominio);
		});
	}
}
