package me.clama.api.identidade;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import me.clama.api.comum.auditoria.RegistroDeAuditoria;
import me.clama.api.comum.cripto.HashIdentificador;
import me.clama.api.conta.ProvisionamentoDeConta;
import me.clama.api.identidade.senha.SenhaDjango;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cria um admin (is_staff + is_clama_admin), para o painel (ClientesAdminController) e para o create_clama_admin. O lock
 * conta-doador: serializa com o provisionamento de doador e a confirmacao do freemium do mesmo e-mail. A duplicidade e
 * canonica: o e-mail em minusculas ou o hash (Gmail sem pontos/+tag), o mesmo casamento do provisionamento; senao a
 * doacao do doador cairia na conta do admin. O chamador valida e-mail e senha. Uma transacao (junta-se a que existir).
 */
@Service
public class CriacaoDeAdmin {
    private static final Object ADICIONADO = List.of(Map.of("added", Map.of()));

    /** Ja existe conta com esse e-mail (ou o canonico dele). Nada foi criado. */
    public static class EmailJaExiste extends RuntimeException {
        EmailJaExiste() {
            super("Usuário com este E-mail já existe.");
        }
    }

    private final TransactionTemplate tx;
    private final UsuarioRepository usuarios;
    private final SenhaDjango senhas;
    private final ProvisionamentoDeConta contas;
    private final RegistroDeAuditoria auditoria;
    private final Clock relogio;
    private final HashIdentificador hash;

    CriacaoDeAdmin(TransactionTemplate tx, UsuarioRepository usuarios, SenhaDjango senhas,
            ProvisionamentoDeConta contas, RegistroDeAuditoria auditoria, Clock relogio, HashIdentificador hash) {
        this.tx = tx;
        this.usuarios = usuarios;
        this.senhas = senhas;
        this.contas = contas;
        this.auditoria = auditoria;
        this.relogio = relogio;
        this.hash = hash;
    }

    /**
     * email ja em minusculas e sem espacos. superusuario: so o resgate do comando (a API nunca). autor: quem audita;
     * null = sem linha no django_admin_log (que exige autor). Devolve o id.
     */
    public long criar(String email, String nome, String senha, boolean superusuario, Long autor) {
        return tx.execute(st -> {
            contas.travarConta(email);
            if (!usuarios.buscarPorEmailOuHash(email, hash.hashEmail(email)).isEmpty()) {
                throw new EmailJaExiste();
            }
            Usuario u = new Usuario();
            u.setEmail(email);
            u.setNomeCompleto(nome == null ? "" : nome);
            u.setPassword(senhas.encode(senha));
            u.setClamaAdmin(true);
            u.setStaff(true);
            u.setSuperuser(superusuario);
            u.setDateJoined(OffsetDateTime.now(relogio));
            long id = usuarios.saveAndFlush(u).getId();
            if (autor != null) {
                auditoria.registrar(autor, "users", "user", String.valueOf(id), String.valueOf(id),
                        RegistroDeAuditoria.ADICAO, ADICIONADO);
            }
            return id;
        });
    }
}
