package me.clama.api.identidade;

import me.clama.api.comum.erro.PastoralException;
import me.clama.api.identidade.token.ServicoDeTokens;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * O delete de User do ORM do Django (mapa 7 §4.3), compartilhado pela tela (D4), pelo lgpd_delete e pelo
 * purgar_dados_blog_customer (etapa 8). As FKs do banco sao DEFERRABLE INITIALLY DEFERRED sem ON DELETE: o CASCADE,
 * SET_NULL e PROTECT de cada uma estao aqui, conferidos contra os models (o FK que faltar estoura no commit). A ordem
 * e por clareza. A blacklist do freemium nao e tocada: quem usou o gratuito continua bloqueado pelo hash.
 */
@Service
public class ExclusaoDeUsuario {
    private final JdbcTemplate jdbc;
    private final UsuarioRepository usuarios;
    private final ServicoDeTokens tokens;

    ExclusaoDeUsuario(JdbcTemplate jdbc, UsuarioRepository usuarios, ServicoDeTokens tokens) {
        this.jdbc = jdbc;
        this.usuarios = usuarios;
        this.tokens = tokens;
    }

    /**
     * Serializa toda escrita que pode reduzir os admins ativos, para a guarda de ultimo admin valer sob concorrencia
     * (dois superusuarios rebaixando ou excluindo um ao outro). Exige transacao ativa. Tomar antes de travar a linha.
     */
    public void travarAdmins() {
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtext('admins'))", Object.class);
    }

    /**
     * Guardas de ultimo admin, para quando o alvo deixa de ser admin ativo (rebaixar, desativar, excluir). Chamar sob
     * travarAdmins(), com a linha do alvo travada. So conta quem entra no painel: is_clama_admin e is_active (o
     * clama_admins() do Django); superusuario sem is_clama_admin nao conta.
     */
    public void exigirOutroAdmin(Usuario u) {
        if (!u.isClamaAdmin() || !u.isActive()) {
            return;
        }
        if (u.isSuperuser() && !existe("select 1 from users_user where id <> ? and is_superuser and is_clama_admin "
                + "and is_active", u.getId())) {
            throw conflito("ultimo_superusuario", "Last active superuser",
                    "Essa é a última conta de superusuário ativa no painel. Torne outra conta superusuária antes.");
        }
        if (!existe("select 1 from users_user where id <> ? and is_clama_admin and is_active", u.getId())) {
            throw conflito("ultimo_admin", "Last active admin",
                    "Essa é a última conta de admin ativa. Crie outro admin antes.");
        }
    }

    /** Comentario.customer e Reacao.customer (CASCADE). O usuario, os pedidos e os bans ficam. */
    @Transactional
    public void purgarBlog(long id) {
        jdbc.update("delete from blog_comentario where customer_id = ?", id);
        jdbc.update("delete from blog_reacao where customer_id = ?", id);
    }

    /** false se o usuario nao existe. 409 pastoral nas guardas; nada e apagado. */
    @Transactional
    public boolean excluir(long id) {
        travarAdmins();
        Usuario u = usuarios.travar(id).orElse(null);
        if (u == null) {
            return false;
        }
        exigirOutroAdmin(u);
        // PROTECT: CustomerBanido.banido_por e Post.autor
        if (existe("select 1 from blog_customerbanido where banido_por_id = ?", id)) {
            throw conflito("usuario_baniu_alguem", "User is banido_por of a ban",
                    "Essa conta baniu alguém no blog, e o registro do ban precisa dela. Ela pode ser desativada.");
        }
        if (existe("select 1 from blog_post where autor_id = ?", id)) {
            throw conflito("usuario_autor_de_post", "User is autor of a post",
                    "Essa conta é autora de um post do blog. Ela pode ser desativada.");
        }
        // OutstandingToken.user: SET_NULL. Revogar antes e melhoria (o Django so solta o vinculo); BlacklistedToken
        // pende do OutstandingToken, nao do usuario, e fica.
        tokens.revogarTodos(id);
        jdbc.update("update token_blacklist_outstandingtoken set user_id = null where user_id = ?", id);
        // Pedido.user: SET_NULL. O pedido e o que pende dele (token freemium, repasse, webhook) ficam intactos.
        jdbc.update("update orders_pedido set user_id = null where user_id = ?", id);
        purgarBlog(id);
        // CustomerBanido.customer: CASCADE; CustomerBanido.revogado_por: SET_NULL
        jdbc.update("delete from blog_customerbanido where customer_id = ?", id);
        jdbc.update("update blog_customerbanido set revogado_por_id = null where revogado_por_id = ?", id);
        // LogEntry.user (CASCADE: some a trilha do proprio admin, como no Django) e os M2M groups/user_permissions
        jdbc.update("delete from django_admin_log where user_id = ?", id);
        jdbc.update("delete from users_user_groups where user_id = ?", id);
        jdbc.update("delete from users_user_user_permissions where user_id = ?", id);
        jdbc.update("delete from users_user where id = ?", id);
        return true;
    }

    private boolean existe(String sql, long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists (" + sql + ")", Boolean.class, id));
    }

    private static PastoralException conflito(String codigo, String msg, String pastoral) {
        return new PastoralException(409, codigo, msg, pastoral);
    }
}
