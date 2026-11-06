package me.clama.api.conta;

import java.time.Duration;
import me.clama.api.comum.email.EnviadorDeEmail;
import me.clama.api.comum.email.ResultadoEnvio;
import me.clama.api.comum.email.modelo.ModelosDeEmail;
import me.clama.api.comum.fila.TipoDeJob;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.oracao.SenhasTemporarias;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * enviar_email_boas_vindas_doador_task (notifications/tasks.py:393-485). A senha temporaria no cache e o "ainda nao
 * enviado": sem ela (ja enviado, expirou, ou o processo reiniciou) e no-op. Envia com a chave {@code boas-vindas/<id>}
 * (a Resend responde "ja enviado" a uma retentativa depois de envio sem confirmacao) e so entao apaga a senha.
 * Retentativas 30/60/120 s; esgotadas, so o Sentry do observador da fila (a senha fica ate o TTL, como no Django).
 */
@Component
public class JobBoasVindas implements TipoDeJob<Long> {
    private static final Logger LOG = LoggerFactory.getLogger(JobBoasVindas.class);
    public static final String NOME = "boas-vindas";

    private final UsuarioRepository usuarios;
    private final SenhasTemporarias senhas;
    private final ModelosDeEmail modelos;
    private final EnviadorDeEmail enviador;
    private final Duration espera;

    JobBoasVindas(UsuarioRepository usuarios, SenhasTemporarias senhas, ModelosDeEmail modelos,
            EnviadorDeEmail enviador, @Value("${clama.conta.boas-vindas-espera:30s}") Duration espera) {
        this.usuarios = usuarios;
        this.senhas = senhas;
        this.modelos = modelos;
        this.enviador = enviador;
        this.espera = espera;
    }

    public static String instancia(Long userId) {
        return NOME + "/" + userId;
    }

    @Override
    public String nome() {
        return NOME;
    }

    @Override
    public Class<Long> payload() {
        return Long.class;
    }

    @Override
    public Duration esperaEntreTentativas() {
        return espera;
    }

    @Override
    public double fatorDeBackoff() {
        return 2.0;
    }

    @Override
    public void executar(Long userId, int retentativa) {
        Usuario u = usuarios.findById(userId).orElse(null);
        if (u == null) {
            LOG.warn("enviar_email_boas_vindas_doador_user_nao_encontrado (event=doador_boas_vindas_user_not_found, "
                    + "user_id={})", userId);
            return;
        }
        String chave = ProvisionamentoDeConta.PREFIXO_SENHA + userId;
        String senha = senhas.ler(chave).orElse("");
        if (senha.isEmpty()) {
            LOG.info("enviar_email_boas_vindas_doador_idempotente (event=doador_boas_vindas_sem_senha_cache, "
                    + "user_id={})", userId);
            return;
        }
        ResultadoEnvio r = enviador.enviar(modelos.boasVindasDoador(u.getEmail(), u.getNomeCompleto(), senha,
                modelos.loginUrl()).comChave("boas-vindas/" + userId));
        if (r == ResultadoEnvio.JA_ENVIADO) {
            LOG.warn("E-mail ja enviado antes (idempotencia da Resend) (event=email_already_sent, user_id={})", userId);
        }
        senhas.apagar(chave);
        LOG.info("enviar_email_boas_vindas_doador_enviado (event=doador_boas_vindas_email_sent, user_id={}, attempt={})",
                userId, retentativa + 1);
    }
}
