package me.clama.api.blog.tarefas;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.clama.api.comum.agenda.ModoDeExecucao;
import me.clama.api.comum.agenda.TarefaAgendada;
import me.clama.api.comum.cripto.CampoCriptografado;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * purgar_ips_antigos (blog/tasks.py:275-303): comentario com created_at &lt; agora - 180 dias perde o IP (LGPD/Marco
 * Civil). O IP e cifrado, entao decifra cada um e so conta os preenchidos; o UPDATE grava o Fernet de "" e nao toca em
 * updated_at (QuerySet.update). Um UPDATE por linha, sem transacao unica, como no Django. Comeca em LISTAR (R6): so
 * conta, e n_purgados e quantos seriam purgados.
 */
@Component
public class PurgaDeIps implements TarefaAgendada {
    private static final Logger LOG = LoggerFactory.getLogger(PurgaDeIps.class);
    public static final String NOME = "purgar-ips";
    static final Duration RETENCAO = Duration.ofDays(180);
    private static final CampoCriptografado CIFRA = new CampoCriptografado();

    private final JdbcTemplate jdbc;
    private final Clock relogio;

    PurgaDeIps(JdbcTemplate jdbc, Clock relogio) {
        this.jdbc = jdbc;
        this.relogio = relogio;
    }

    @Override
    public String nome() {
        return NOME;
    }

    @Override
    public boolean alteraDados() {
        return true;
    }

    private record Candidato(UUID id, String ip) {}

    @Override
    public ResultadoDaTarefa executar(ModoDeExecucao modo) {
        List<Candidato> candidatos = jdbc.query("select id, ip_address from blog_comentario where created_at < ?"
                + " order by created_at, id", (rs, i) -> new Candidato(rs.getObject(1, UUID.class),
                        CIFRA.convertToEntityAttribute(rs.getString(2))),
                Timestamp.from(relogio.instant().minus(RETENCAO)));
        int purgados = 0;
        for (Candidato c : candidatos) {
            if (c.ip() == null || c.ip().isEmpty()) continue;
            if (modo == ModoDeExecucao.EXECUTAR) {
                jdbc.update("update blog_comentario set ip_address = ? where id = ?",
                        CIFRA.convertToDatabaseColumn(""), c.id());
            }
            purgados++;
        }
        if (modo == ModoDeExecucao.EXECUTAR) {
            LOG.info("purgar_ips_antigos_done (n_purgados={})", purgados);
        } else {
            LOG.info("purgar_ips_antigos (modo LISTAR, nada alterado): seriam purgados {}", purgados);
        }
        return new ResultadoDaTarefa(NOME, modo, Map.of("n_purgados", purgados));
    }
}
