package me.clama.api.identidade.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import me.clama.api.BancoDeTeste;
import me.clama.api.comum.cripto.CriptoConfig;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Sem a transacao de teste: duas transacoes reais disputam o mesmo refresh. */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({BancoDeTeste.class, CriptoConfig.class, ServicoDeTokens.class, Jwt.class})
@ActiveProfiles("test")
class RotacaoConcorrenteTest {
    @Autowired ServicoDeTokens servico;
    @Autowired UsuarioRepository usuarios;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    @AfterEach
    void limpa() {
        BancoDeTeste.exigirContainer(jdbc);
        jdbc.update("delete from token_blacklist_blacklistedtoken");
        jdbc.update("delete from token_blacklist_outstandingtoken");
        jdbc.update("delete from users_user");
    }

    @Test
    void rotacaoConcorrenteNaoEstoura() throws Exception {
        Usuario u = new Usuario();
        u.setEmail("c@x.com");
        u.setPassword("x");
        usuarios.saveAndFlush(u);
        String refresh = servico.emitirPar(u).refresh();

        CountDownLatch aRevogou = new CountDownLatch(1);
        ExecutorService ex = Executors.newFixedThreadPool(2);
        // A revoga e segura a transacao aberta; B passa na checagem (A nao commitou) e bate no UNIQUE.
        Future<?> a = ex.submit(() -> tx.executeWithoutResult(s -> {
            servico.rotacionar(refresh);
            aRevogou.countDown();
            try {
                Thread.sleep(800);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }));
        Future<?> b = ex.submit(() -> {
            aRevogou.await();
            return servico.rotacionar(refresh);
        });
        for (Future<?> f : new Future<?>[] {a, b}) {
            try {
                f.get(20, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(JwtInvalido.class);
            }
        }
        ex.shutdown();
        assertThat(jdbc.queryForObject("""
                select count(*) from token_blacklist_blacklistedtoken b
                join token_blacklist_outstandingtoken o on o.id = b.token_id where o.token = ?""",
                Integer.class, refresh)).isEqualTo(1);
    }
}
