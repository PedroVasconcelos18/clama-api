package me.clama.api.pedido.comando;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.clama.api.comum.comando.Chamada;
import me.clama.api.comum.comando.Comando;
import me.clama.api.comum.comando.Comandos.Opcao;
import me.clama.api.comum.cripto.Fernet;
import me.clama.api.comum.cripto.HashIdentificador;
import me.clama.api.comum.cripto.TokenInvalido;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * adotar_pedidos_orfaos (mapa §5): liga pedido sem usuario ao dono do e-mail. O e-mail do pedido e cifrado, entao a
 * comparacao e em memoria, pelo hash canonico (Gmail sem pontos/+tag), o mesmo do provisionamento. Diferencas do Django:
 * conta ambigua (mais de um usuario com o mesmo hash) e pulada e contada; o UPDATE so toca quem ainda esta sem usuario
 * (sem corrida com o provisionamento); nao ha alerta de admin ao adotar pedido em erro. O stdout leva o e-mail em claro
 * (paridade); log e Sentry nunca.
 */
@Component
public class AdotarPedidosOrfaos implements Comando {
    public static final List<Opcao> OPCOES = List.of(Opcao.flag("--dry-run"), Opcao.valor("--email"));
    private static final Logger LOG = LoggerFactory.getLogger(AdotarPedidosOrfaos.class);
    private static final int AMOSTRA = 20;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Fernet fernet;
    private final HashIdentificador hash;

    AdotarPedidosOrfaos(JdbcTemplate jdbc, TransactionTemplate tx, Fernet fernet, HashIdentificador hash) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.fernet = fernet;
        this.hash = hash;
    }

    record Vinculo(UUID pedido, String email, long usuario) {}

    @Override
    public int executar(Chamada c) {
        boolean seco = c.tem("--dry-run");
        String soEmail = HashIdentificador.normalizarEmail(c.valor("--email"));

        // ordering do model: -created_at
        List<Object[]> orfaos = jdbc.query(
                "select id, email from orders_pedido where user_id is null order by created_at desc, id",
                (rs, n) -> new Object[] {rs.getObject("id", UUID.class), rs.getString("email")});
        // o rotulo mente como no Django: conta todos os orfaos, com ou sem e-mail, e ignora --email
        c.out().println("Pedidos órfãos com e-mail: " + orfaos.size());
        if (orfaos.isEmpty()) {
            return 0;
        }

        Map<String, List<Long>> porHash = new HashMap<>();
        jdbc.query("select id, email_hash from users_user where email <> '' and email_hash <> '' order by id",
                rs -> {
                    porHash.computeIfAbsent(rs.getString("email_hash"), k -> new ArrayList<>()).add(rs.getLong("id"));
                });

        List<Vinculo> vinculaveis = new ArrayList<>();
        int semConta = 0;
        int ambiguos = 0;
        for (Object[] o : orfaos) {
            UUID id = (UUID) o[0];
            String email = decifrar(id, (String) o[1]);
            String normalizado = HashIdentificador.normalizarEmail(email);
            if (normalizado.isEmpty()) {
                semConta++;
                continue;
            }
            if (!soEmail.isEmpty() && !normalizado.equals(soEmail)) {
                continue;
            }
            List<Long> donos = porHash.getOrDefault(hash.hashEmail(email), List.of());
            if (donos.size() > 1) {
                ambiguos++;
            } else if (donos.isEmpty()) {
                semConta++;
            } else {
                vinculaveis.add(new Vinculo(id, email, donos.get(0)));
            }
        }

        c.out().println("  com conta correspondente: " + vinculaveis.size());
        c.out().println("  sem conta (ficam órfãos):  " + semConta);
        if (ambiguos > 0) {
            c.out().println("  ambíguos (mais de uma conta com o e-mail, ficam órfãos): " + ambiguos);
        }
        vinculaveis.stream().limit(AMOSTRA)
                .forEach(v -> c.out().println("    " + v.pedido() + " · " + v.email() + " → user " + v.usuario()));
        if (vinculaveis.size() > AMOSTRA) {
            c.out().println("    … e mais " + (vinculaveis.size() - AMOSTRA));
        }

        if (seco) {
            c.out().println();
            c.out().println("--dry-run: nada foi gravado.");
            return 0;
        }

        // uma transacao: ou vincula tudo, ou nada. user_id is null: nao sobrescreve o provisionamento concorrente.
        int vinculados = tx.execute(st -> {
            int n = 0;
            for (Vinculo v : vinculaveis) {
                n += jdbc.update("update orders_pedido set user_id = ? where id = ? and user_id is null", v.usuario(),
                        v.pedido());
            }
            return n;
        });
        LOG.info("Pedidos órfãos adotados: vinculados={} candidatos={} sem_conta={} ambiguos={}", vinculados,
                vinculaveis.size(), semConta, ambiguos);
        c.out().println();
        c.out().println(vinculados + " pedido(s) vinculado(s).");
        if (vinculados < vinculaveis.size()) {
            c.out().println((vinculaveis.size() - vinculados) + " já tinham sido vinculado(s) por outro processo.");
        }
        return 0;
    }

    /** Vazio quando nao decifra: o pedido fica orfao e sai na conta de "sem conta" (so o id no log, nunca o e-mail). */
    private String decifrar(UUID pedido, String cifrado) {
        if (cifrado == null || cifrado.isEmpty()) {
            return "";
        }
        try {
            return fernet.decifrar(cifrado);
        } catch (TokenInvalido e) {
            LOG.warn("Pedido {} com e-mail que não decifra; fica órfão", pedido);
            return "";
        }
    }
}
