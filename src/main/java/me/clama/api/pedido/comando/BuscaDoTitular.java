package me.clama.api.pedido.comando;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import me.clama.api.comum.comando.ErroDeComando;
import me.clama.api.comum.cripto.Fernet;
import me.clama.api.comum.cripto.HashIdentificador;
import me.clama.api.comum.cripto.TokenInvalido;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Busca do lgpd_* (D1): os pedidos cujo e-mail decifrado e a mesma caixa (forma canonica: Gmail sem pontos e +tag),
 * mais os pedidos das contas da mesma caixa (pelo e-mail em claro ou pelo email_hash). orders_pedido nao tem
 * email_hash: so a varredura acha o pedido orfao. O e-mail nunca vai para o log.
 */
@Component
class BuscaDoTitular {
    private static final Logger LOG = LoggerFactory.getLogger(BuscaDoTitular.class);
    private static final Pattern EMAIL = Pattern.compile("[^@\\s/]+@[^@\\s/]+");

    private final JdbcTemplate jdbc;
    private final Fernet fernet;
    private final HashIdentificador hash;

    BuscaDoTitular(JdbcTemplate jdbc, Fernet fernet, HashIdentificador hash) {
        this.jdbc = jdbc;
        this.fernet = fernet;
        this.hash = hash;
    }

    /** pedidos do mais novo para o mais antigo (ordering do model); contas por id. */
    record Titular(List<UUID> pedidos, List<Long> contas) {
        boolean vazio() {
            return pedidos.isEmpty() && contas.isEmpty();
        }
    }

    /**
     * email ja em minusculas e sem espacos. Vazio ou sem @ e recusado: casaria com todo pedido anonimizado (cifra de
     * "") e com conta sem e-mail.
     */
    Titular buscar(String email) {
        if (!EMAIL.matcher(email).matches()) {
            throw new ErroDeComando("E-mail inválido. Nada foi feito.");
        }
        String alvo = HashIdentificador.normalizarEmail(email);
        String alvoHash = hash.hashEmail(alvo);
        List<Long> contas = new ArrayList<>();
        jdbc.query("select id, email, email_hash from users_user order by id", rs -> {
            if (HashIdentificador.normalizarEmail(rs.getString("email")).equals(alvo)
                    || alvoHash.equals(rs.getString("email_hash"))) {
                contas.add(rs.getLong("id"));
            }
        });
        // ponytail: decifra todos os pedidos (67 em prod, rapido ate ~10^5). Passou disso: coluna email_hash em
        // orders_pedido (mudanca de schema, depois da virada).
        List<UUID> pedidos = new ArrayList<>();
        jdbc.query("select id, email, user_id from orders_pedido order by created_at desc, id", rs -> {
            UUID id = rs.getObject("id", UUID.class);
            long user = rs.getLong("user_id");
            boolean daConta = !rs.wasNull() && contas.contains(user);
            if (daConta || HashIdentificador.normalizarEmail(decifrar(id, rs.getString("email"))).equals(alvo)) {
                pedidos.add(id);
            }
        });
        return new Titular(pedidos, contas);
    }

    /** Como o CampoCriptografado: o que nao decifra (texto puro legado) volta cru. So o id no log. */
    private String decifrar(UUID pedido, String cifrado) {
        if (cifrado == null || cifrado.isEmpty()) {
            return "";
        }
        try {
            return fernet.decifrar(cifrado);
        } catch (TokenInvalido e) {
            LOG.warn("Pedido {} com e-mail que não decifra; comparado como texto puro", pedido);
            return cifrado;
        }
    }

    /** Para o stdout: "m***@exemplo.com". */
    static String mascarar(String email) {
        int at = email.indexOf('@');
        return at <= 0 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }
}
