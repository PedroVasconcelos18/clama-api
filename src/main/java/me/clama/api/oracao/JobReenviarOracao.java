package me.clama.api.oracao;

import java.time.Duration;
import java.util.UUID;
import me.clama.api.comum.fila.TipoDeJob;
import org.springframework.stereotype.Component;

/**
 * O reenviar do admin (pendencia da 4B). No Django ele enfileira o mesmo enviar_oracao_task, cuja chave fixa faria a
 * Resend devolver o envio anterior; aqui cada reenvio tem o seu {@code envio} e a chave
 * {@code oracao/<id>/reenvio/<envio>} (estavel nas retentativas). O resto e o {@link JobEnviarOracao}: mesmas esperas,
 * mesma marcacao e o mesmo esgotou (erro e alerta). Instancia {@code reenviar-oracao/<id>}: um reenvio por pedido na
 * fila.
 */
@Component
public class JobReenviarOracao implements TipoDeJob<JobReenviarOracao.ReenvioDeOracao> {
    public static final String NOME = "reenviar-oracao";

    /** Payload so com IDs: o pedido e o envio (aleatorio, a chave nova do e-mail). */
    public record ReenvioDeOracao(UUID pedido, UUID envio) {}

    private final JobEnviarOracao enviar;

    JobReenviarOracao(JobEnviarOracao enviar) {
        this.enviar = enviar;
    }

    public static String instancia(UUID pedido) {
        return NOME + "/" + pedido;
    }

    @Override
    public String nome() {
        return NOME;
    }

    @Override
    public Class<ReenvioDeOracao> payload() {
        return ReenvioDeOracao.class;
    }

    @Override
    public Duration esperaEntreTentativas() {
        return enviar.esperaEntreTentativas();
    }

    @Override
    public double fatorDeBackoff() {
        return enviar.fatorDeBackoff();
    }

    @Override
    public void executar(ReenvioDeOracao r, int retentativa) {
        enviar.enviar(r.pedido(), r.envio(), retentativa);
    }

    @Override
    public void esgotou(ReenvioDeOracao r, Throwable erro) {
        enviar.esgotou(r.pedido(), erro);
    }
}
