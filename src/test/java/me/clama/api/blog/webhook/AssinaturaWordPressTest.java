package me.clama.api.blog.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * verificar_assinatura_webhook (blog/services/wordpress_webhook.py:86-137). Vetores do Python do clama-backend:
 *
 * <pre>
 * .venv/bin/python -c "import hmac,hashlib; print(hmac.new(b'segredo-wp', b'evento_id=wp-1&amp;tipo=post_publicado',
 *     hashlib.sha256).hexdigest())"   # 28b26642...
 * </pre>
 */
class AssinaturaWordPressTest {
    static final String SEGREDO = "segredo-wp";
    static final byte[] CORPO = "evento_id=wp-1&tipo=post_publicado".getBytes(StandardCharsets.UTF_8);
    static final String OK = "28b26642aa04ccd5d2df21f5c1db80e9e99a3220acfac00cd0a1b3f8c7bf4882";

    @Test
    void vetorDoPythonComESemPrefixo() {
        assertThat(AssinaturaWordPress.valida(SEGREDO, CORPO, OK)).isTrue();
        assertThat(AssinaturaWordPress.valida(SEGREDO, CORPO, "sha256=" + OK)).isTrue();
        assertThat(AssinaturaWordPress.valida(SEGREDO, new byte[0], "b0a76a4f3dd4cfa2ea5c233c3e80444fcba15593aa463a1d"
                + "2382c1592320818d")).as("corpo vazio (GET) tambem assina").isTrue();
    }

    @Test
    void assinaturaMaiusculaNaoCasa() {
        assertThat(AssinaturaWordPress.valida(SEGREDO, CORPO, OK.toUpperCase())).isFalse();
        assertThat(AssinaturaWordPress.valida(SEGREDO, CORPO, "SHA256=" + OK)).as("prefixo e exato").isFalse();
    }

    @Test
    void recusa() {
        assertThat(AssinaturaWordPress.valida(SEGREDO, CORPO, null)).as("sem header").isFalse();
        assertThat(AssinaturaWordPress.valida(SEGREDO, CORPO, "")).as("header vazio").isFalse();
        assertThat(AssinaturaWordPress.valida(SEGREDO, CORPO, "é" + OK.substring(1))).as("nao-ASCII").isFalse();
        assertThat(AssinaturaWordPress.valida(SEGREDO, CORPO, OK + " ")).as("sem strip").isFalse();
        assertThat(AssinaturaWordPress.valida("", CORPO, OK)).as("segredo vazio").isFalse();
        assertThat(AssinaturaWordPress.valida(SEGREDO, "evento_id=wp-2&tipo=post_publicado".getBytes(
                StandardCharsets.UTF_8), OK)).as("outro corpo").isFalse();
    }

    @Test
    void semSegredoNoBootFalhaForaDeTest() {
        assertThatThrownBy(() -> new AssinaturaWordPress("", false)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WORDPRESS_WEBHOOK_SECRET");
        assertThatThrownBy(() -> new AssinaturaWordPress("  ", false)).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> new AssinaturaWordPress("", true)).doesNotThrowAnyException();
        assertThatCode(() -> new AssinaturaWordPress("x", false)).doesNotThrowAnyException();
    }
}
