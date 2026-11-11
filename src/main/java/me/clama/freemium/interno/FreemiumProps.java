package me.clama.freemium.interno;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code clama.freemium.*}: {@code limiarIp} bloqueios do mesmo IP dentro de {@code janelaIp} (inclusivo) barram o
 * submit; {@code validadeLink} e a vida do link de confirmacao; {@code janelaPendente} e quanto um pedido aguardando
 * confirmacao impede outro; {@code frontendUrl} e a base do link do e-mail; {@code esperaEmail} e a 1a espera das
 * retentativas do job do e-mail (dobra a cada uma).
 */
@ConfigurationProperties("clama.freemium")
public record FreemiumProps(@DefaultValue("3") int limiarIp, @DefaultValue("1h") Duration janelaIp,
		@DefaultValue("24h") Duration validadeLink, @DefaultValue("24h") Duration janelaPendente,
		@DefaultValue("http://localhost:5173") String frontendUrl, @DefaultValue("30s") Duration esperaEmail) {}
