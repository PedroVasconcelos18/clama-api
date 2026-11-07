package me.clama.api.oracao;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import me.clama.api.comum.cripto.Fernet;
import me.clama.api.comum.cripto.TokenInvalido;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * O cache de senhas temporarias do Django ({@code doador:temp_password:<userId>}, {@code freemium:temp_password:<userId>}):
 * cifradas com Fernet ({@code FREEMIUM_TEMP_PWD_KEY}), TTL de 24 h. Nao compartilha o Redis/pickle do Django: no
 * cutover (ou num restart entre o webhook e o job) a senha se perde e o e-mail sai sem credenciais, como na perda do
 * cache do Django.
 */
// ponytail: em memoria, uma instancia; uma tabela aditiva se escalar para mais de uma
@Component
public class SenhasTemporarias {
    private static final Logger LOG = LoggerFactory.getLogger(SenhasTemporarias.class);
    static final Duration TTL = Duration.ofHours(24);

    private record Entrada(String token, Instant expira) {}

    private final Map<String, Entrada> cache = new ConcurrentHashMap<>();
    private final Fernet fernet;

    /** Sem a env, uma chave aleatoria por processo basta: o cache tambem so vive no processo. */
    SenhasTemporarias(@Value("${FREEMIUM_TEMP_PWD_KEY:}") String chave) {
        if (chave.isBlank()) {
            byte[] k = new byte[32];
            new SecureRandom().nextBytes(k);
            chave = Base64.getUrlEncoder().encodeToString(k);
        }
        fernet = new Fernet(chave);
    }

    public void guardar(String chave, String senha) {
        Instant agora = Instant.now();
        cache.values().removeIf(e -> e.expira().isBefore(agora));
        cache.put(chave, new Entrada(fernet.cifrar(senha), agora.plus(TTL)));
    }

    /**
     * guardar ja, antes do commit (quem le so ve a conta na fila com o commit, entao nunca a le sem a senha), e apagar
     * se a transacao desfizer (o id da conta desfeita nunca volta, mas nao fica lixo). Commit de resultado desconhecido
     * mantem: senha a mais e inofensiva, conta sem senha perderia o e-mail. Exige transacao ativa.
     */
    public void guardarNaTransacao(String chave, String senha) {
        guardar(chave, senha);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    apagar(chave);
                }
            }
        });
    }

    /** Ausente, expirada ou indecifravel (desencriptar_senha_do_cache devolve "": warning e Sentry) = vazio. */
    public Optional<String> ler(String chave) {
        Entrada e = cache.get(chave);
        if (e == null || e.expira().isBefore(Instant.now())) return Optional.empty();
        try {
            return Optional.of(fernet.decifrar(e.token()));
        } catch (TokenInvalido x) {
            LOG.warn("Falha ao decriptar senha temporária do freemium (event=freemium_temp_pwd_decrypt_failed)");
            Sentry.captureMessage("Falha ao decriptar senha temporária do freemium (chave rotacionada?)",
                    SentryLevel.WARNING);
            return Optional.empty();
        }
    }

    public void apagar(String chave) {
        cache.remove(chave);
    }

    /** cache.clear(): so testes. */
    public void limpar() {
        cache.clear();
    }
}
