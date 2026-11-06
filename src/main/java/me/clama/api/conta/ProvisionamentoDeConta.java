package me.clama.api.conta;

import me.clama.api.comum.cripto.HashIdentificador;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.senha.SenhaDjango;
import me.clama.api.oracao.SenhasTemporarias;
import me.clama.api.pedido.Pedido;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * provisionar_ou_vincular_conta_doador (customers/services/account_provisioning.py): na transacao do webhook, com o
 * pedido travado. Pedido ja vinculado: no-op. Acha a conta pelo e-mail normalizado (Gmail canonico) sem caixa ou pelo
 * email_hash e so vincula (sem mexer na senha nem mandar e-mail: anti-enumeracao); senao cria com senha temporaria e
 * force_change_password, gravando o e-mail canonico em users_user.email.
 *
 * <p>Corrida (duas doacoes do mesmo e-mail confirmadas juntas): um advisory lock transacional pelo e-mail normalizado
 * serializa busca e criacao, entao a 2a ve a conta da 1a depois do commit e so vincula. O Django resolve com savepoint
 * e nova busca depois do IntegrityError; aqui a violacao de unique so viria de outro escritor fora deste lock, e ai
 * derruba a transacao do webhook (500, ERRO, o MP reenvia e a nova busca vincula).
 */
@Component
public class ProvisionamentoDeConta {
    private static final Logger LOG = LoggerFactory.getLogger(ProvisionamentoDeConta.class);
    /** DOADOR_TEMP_PASSWORD_CACHE_PREFIX. */
    public static final String PREFIXO_SENHA = "doador:temp_password:";

    /** usuario nulo: pedido sem e-mail. criada: a conta nasceu agora (so entao vai o e-mail de boas-vindas). */
    public record Conta(Usuario usuario, boolean criada) {}

    private final UsuarioRepository usuarios;
    private final HashIdentificador hash;
    private final SenhaDjango senhaDjango;
    private final SenhasTemporarias senhas;
    private final JdbcTemplate jdbc;

    ProvisionamentoDeConta(UsuarioRepository usuarios, HashIdentificador hash, SenhaDjango senhaDjango,
            SenhasTemporarias senhas, JdbcTemplate jdbc) {
        this.usuarios = usuarios;
        this.hash = hash;
        this.senhaDjango = senhaDjango;
        this.senhas = senhas;
        this.jdbc = jdbc;
    }

    /** Exige transacao ativa e o pedido gerenciado (o vinculo vai no flush do commit). */
    public Conta provisionarOuVincular(Pedido p) {
        if (p.getUser() != null) {
            LOG.info("Provisionamento de doador ignorado — pedido já vinculado "
                    + "(event=doador_provisionamento_ja_vinculado, pedido_id={})", p.getId());
            return new Conta(p.getUser(), false);
        }
        String email = HashIdentificador.normalizarEmail(p.getEmail());
        if (email.isEmpty()) {
            LOG.info("Provisionamento de doador sem e-mail — nada a fazer (event=doador_provisionamento_sem_email, "
                    + "pedido_id={})", p.getId());
            return new Conta(null, false);
        }
        String dominio = email.contains("@") ? email.substring(email.lastIndexOf('@') + 1) : "?";
        String emailHash = hash.hashEmail(email);
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtext(?))", Object.class, "conta-doador:" + email);

        Usuario u = usuarios.buscarPorEmailOuHash(email, emailHash).stream().findFirst().orElse(null);
        if (u != null) {
            p.setUser(u);
            LOG.info("Doação vinculada a conta existente (event=doador_vinculado_conta_existente, pedido_id={}, "
                    + "email_dominio={}, email_hash={})", p.getId(), dominio, emailHash);
            return new Conta(u, false);
        }

        String senha = GeradorDeSenhaTemporaria.gerar();
        u = new Usuario();
        u.setEmail(email);
        u.setPassword(senhaDjango.encode(senha));
        u.setNomeCompleto(p.getNome() == null ? "" : p.getNome());
        u.setCpfCnpj(p.getCpfCnpj());
        u.setTelefone(p.getTelefone());
        u.setIdade(p.getIdade());
        u.setSexo(p.getSexo());
        u.setForceChangePassword(true);
        usuarios.saveAndFlush(u);
        p.setUser(u);
        guardarSenha(u.getId(), senha);
        LOG.info("Conta de doador provisionada (event=doador_conta_provisionada, pedido_id={}, user_id={}, "
                + "email_dominio={}, email_hash={})", p.getId(), u.getId(), dominio, emailHash);
        return new Conta(u, true);
    }

    /**
     * A senha entra no cache ja, antes do commit: o boas-vindas so fica visivel na fila com o commit, entao nunca le a
     * conta sem a senha. Rollback apaga (o id da conta desfeita nunca volta, mas nao fica lixo). Commit de resultado
     * desconhecido mantem: senha a mais e inofensiva, conta sem senha perderia o e-mail.
     */
    private void guardarSenha(Long userId, String senha) {
        String chave = PREFIXO_SENHA + userId;
        senhas.guardar(chave, senha);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    senhas.apagar(chave);
                }
            }
        });
    }
}
