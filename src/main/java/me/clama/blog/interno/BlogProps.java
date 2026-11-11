package me.clama.blog.interno;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code clama.blog.*}: {@code wordpressUrl} e a base da API publica do WordPress (vazio = indisponivel); {@code timeout} vale para conectar e para ler. */
@ConfigurationProperties("clama.blog")
public record BlogProps(@DefaultValue("") String wordpressUrl, @DefaultValue("10s") Duration timeout) {}
