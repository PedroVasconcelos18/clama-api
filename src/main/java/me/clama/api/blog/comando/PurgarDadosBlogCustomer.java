package me.clama.api.blog.comando;

import static me.clama.api.comum.texto.TextoPython.repr;

import java.io.IOException;
import java.util.List;
import me.clama.api.comum.comando.Chamada;
import me.clama.api.comum.comando.Comando;
import me.clama.api.comum.comando.Comandos.Opcao;
import me.clama.api.comum.comando.ErroDeComando;
import me.clama.api.identidade.ExclusaoDeUsuario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * purgar_dados_blog_customer (mapa §7): apaga comentarios e reacoes de um customer (LGPD); User, Pedido e
 * CustomerBanido ficam. Textos sem acento, como no Django. Corrigido: dois usuarios que so diferem na caixa dao
 * ErroDeComando (o Django morria com traceback), e EOF no prompt tambem. O log leva o id e as contagens, nunca o e-mail.
 */
@Component
public class PurgarDadosBlogCustomer implements Comando {
    public static final List<Opcao> OPCOES = List.of(Opcao.posicional("email"), Opcao.flag("--dry-run"),
            Opcao.flag("--yes"));
    private static final Logger LOG = LoggerFactory.getLogger(PurgarDadosBlogCustomer.class);

    private final JdbcTemplate jdbc;
    private final ExclusaoDeUsuario exclusao;

    PurgarDadosBlogCustomer(JdbcTemplate jdbc, ExclusaoDeUsuario exclusao) {
        this.jdbc = jdbc;
        this.exclusao = exclusao;
    }

    record Alvo(long id, String email) {}

    @Override
    public int executar(Chamada c) throws IOException {
        String digitado = c.valor("email");
        boolean seco = c.tem("--dry-run");

        List<Alvo> achados = jdbc.query("select id, email from users_user where lower(email) = lower(?) order by id",
                (rs, n) -> new Alvo(rs.getLong("id"), rs.getString("email")), digitado);
        if (achados.isEmpty()) {
            throw new ErroDeComando("Customer com email " + repr(digitado) + " nao encontrado.");
        }
        if (achados.size() > 1) {
            throw new ErroDeComando("Mais de uma conta com o email " + repr(digitado)
                    + " (diferem so na caixa). Nada foi apagado.");
        }
        Alvo u = achados.get(0);

        if (!seco && !c.tem("--yes")) {
            c.out().println("Voce esta prestes a apagar TODOS os comentarios e reacoes de " + u.email() + " (user_id="
                    + u.id() + "). User account e pedidos NAO serao tocados.");
            c.out().print("Confirma? [y/N]: ");
            c.out().flush();
            String resposta = c.in().readLine();
            if (resposta == null) {
                c.out().println();
                throw new ErroDeComando("Sem resposta no stdin para a confirmacao; use --yes. Nada foi apagado.");
            }
            if (!resposta.strip().toLowerCase().equals("y")) {
                c.out().println("Cancelado.");
                return 0;
            }
        }

        if (seco) {
            int comentarios = contar("blog_comentario", u.id());
            int reacoes = contar("blog_reacao", u.id());
            c.out().println("[DRY-RUN] Seriam removidos: " + comentarios + " comentarios, " + reacoes + " reacoes de "
                    + u.email() + " (user_id=" + u.id() + "). Nenhuma mudanca persistida.");
            LOG.info("purgar_dados_blog_customer_dry_run user_id={} n_comentarios={} n_reacoes={}", u.id(),
                    comentarios, reacoes);
            return 0;
        }
        ExclusaoDeUsuario.Purga p = exclusao.purgarBlog(u.id());
        c.out().println("Removidos: " + p.comentarios() + " comentarios, " + p.reacoes() + " reacoes de " + u.email()
                + " (user_id=" + u.id() + ")");
        LOG.info("purgar_dados_blog_customer_done user_id={} n_comentarios={} n_reacoes={}", u.id(), p.comentarios(),
                p.reacoes());
        return 0;
    }

    private int contar(String tabela, long customer) {
        Integer n = jdbc.queryForObject("select count(*) from " + tabela + " where customer_id = ?", Integer.class,
                customer);
        return n == null ? 0 : n;
    }
}
