package me.clama.api.pagamento.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import me.clama.api.BancoDeTeste;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** O registro de evento aceita provider variavel (WORDPRESS) sem mexer no do Mercado Pago. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({BancoDeTeste.class, me.clama.api.comum.cripto.CriptoConfig.class})
@ActiveProfiles("test")
class WebhookEventoProviderTest {
    @Autowired JdbcTemplate jdbc;

    @Test
    void registraComProviderVariavelEMantemOPrimeiroPayload() {
        BancoDeTeste.exigirContainer(jdbc);
        var repo = new WebhookEventoRepository(jdbc);
        String id = "wp-" + UUID.randomUUID();
        var novo = repo.registrar(WebhookEventoRepository.WORDPRESS, id, "post.updated", "{\"a\":1}");
        var igual = repo.registrar(WebhookEventoRepository.WORDPRESS, id, "outro", "{\"b\":2}");
        assertThat(igual.id()).isEqualTo(novo.id());
        assertThat(novo.status()).isEqualTo("RECEBIDO");
        assertThat(jdbc.queryForObject("select provider || '/' || event_type || '/' || payload::text "
                + "from payments_webhookevento where id = ?", String.class, novo.id()))
                .isEqualTo("WORDPRESS/post.updated/{\"a\": 1}");
        var mp = repo.registrar(id, "payment", "{}"); // mesmo id externo, outro provider: outra linha
        assertThat(mp.id()).isNotEqualTo(novo.id());
        assertThat(jdbc.queryForObject("select provider from payments_webhookevento where id = ?", String.class,
                mp.id())).isEqualTo("MERCADO_PAGO");
    }
}
