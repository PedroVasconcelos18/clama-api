package me.clama.api.comum.observabilidade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.sentry.Breadcrumb;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.clama.api.BancoDeTeste;
import me.clama.api.comum.fila.TipoDeJob;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "sentry.dsn=https://chave@localhost/1")
@Import({BancoDeTeste.class, RedacaoSentryTest.Config.class})
@ActiveProfiles("test")
class RedacaoSentryTest {
	record Falha() {}

	static final TransportEmMemoria TRANSPORT = new TransportEmMemoria();

	@TestConfiguration(proxyBeanMethods = false)
	static class Config {
		@Bean
		io.sentry.ITransportFactory transportFalso() {
			return TRANSPORT.fabrica();
		}

		@Bean
		TipoDeJob<Falha> jobQueSempreFalha() {
			return new TipoDeJob<>() {
				public String nome() { return "job-que-falha"; }
				public Class<Falha> payload() { return Falha.class; }
				public Duration esperaEntreTentativas() { return Duration.ofMillis(50); }
				public void executar(Falha p) { throw new IllegalStateException("nao enviou para joao@exemplo.com"); }
			};
		}
	}

	@Autowired me.clama.api.comum.fila.Fila fila;
	@Autowired TipoDeJob<Falha> jobQueSempreFalha;
	@Autowired TransactionTemplate tx;
	@Autowired JdbcTemplate jdbc;

	@AfterAll
	static void fechar() {
		Sentry.close();
	}

	// .venv/bin/python (clama-backend), importando clama/core/sentry_config.py e chamando _redact_dict / _redact_value
	@Test
	void redigeComoOPython() {
		Map<String, Object> aninhado = new LinkedHashMap<>();
		aninhado.put("nome", "Maria");
		aninhado.put("cidade", "SP");
		aninhado.put("lista_num", List.of(1, 2));
		Map<String, Object> entrada = new LinkedHashMap<>();
		entrada.put("email", "a@b.com");
		entrada.put("Senha", "x");
		entrada.put("refresh", "tok");
		entrada.put("Authorization", "Bearer abc");
		entrada.put("obs", "fale com joao@x.com.br ou (11) 98765-4321 hoje");
		entrada.put("itens", List.of(Map.of("telefone", "11987654321", "ok", "sim"), "mail a@b.co", "fone 11 3456-7890"));
		entrada.put("n", 5);
		entrada.put("flag", true);
		entrada.put("nulo", null);
		entrada.put("aninhado", aninhado);
		entrada.put("tokens", List.of("a", "b"));

		Map<String, Object> esperado = new LinkedHashMap<>();
		esperado.put("email", "[REDACTED]");
		esperado.put("Senha", "[REDACTED]");
		esperado.put("refresh", "[REDACTED]");
		esperado.put("Authorization", "Bearer abc");
		esperado.put("obs", "fale com [EMAIL_REDACTED] ou [PHONE_REDACTED] hoje");
		esperado.put("itens", List.of(Map.of("telefone", "[REDACTED]", "ok", "sim"), "mail [EMAIL_REDACTED]", "fone [PHONE_REDACTED]"));
		esperado.put("n", 5);
		esperado.put("flag", true);
		esperado.put("nulo", null);
		esperado.put("aninhado", Map.of("nome", "[REDACTED]", "cidade", "SP", "lista_num", List.of(1, 2)));
		esperado.put("tokens", List.of("[REDACTED]", "[REDACTED]"));
		assertThat(RedacaoSentry.dict(entrada)).isEqualTo(esperado);

		assertThat(RedacaoSentry.valor("message", "ligue 11 98765-4321 ou x@y.com")).isEqualTo("ligue [PHONE_REDACTED] ou [EMAIL_REDACTED]");
		assertThat(RedacaoSentry.valor("exception_value", "Oração de Maria <m@x.org>")).isEqualTo("Oração de Maria <[EMAIL_REDACTED]>");
		assertThat(RedacaoSentry.valor("email", 3)).isEqualTo(3);
		assertThat(RedacaoSentry.valor("x", "sem pii")).isEqualTo("sem pii");
	}

	@Test
	@SuppressWarnings("unchecked")
	void redigeOsLocaisDoEvento() {
		var ev = new SentryEvent();
		var req = new Request();
		req.setData(new LinkedHashMap<>(Map.of("email", "a@b.com", "ok", "1")));
		req.setHeaders(new LinkedHashMap<>(Map.of("X-Fone", "11 98765-4321")));
		ev.setRequest(req);
		ev.setExtra("telefone", "11987654321");
		ev.setTag("quem", "a@b.com");
		var bc = new Breadcrumb("ligou 11 98765-4321");
		bc.setData("senha", "x");
		ev.addBreadcrumb(bc);
		var ex = new SentryException();
		ex.setValue("falhou para a@b.com");
		ev.setExceptions(new ArrayList<>(List.of(ex)));

		RedacaoSentry.redigir(ev);

		assertThat((Map<String, Object>) req.getData()).containsEntry("email", "[REDACTED]").containsEntry("ok", "1");
		assertThat(req.getHeaders()).containsEntry("X-Fone", "[PHONE_REDACTED]");
		assertThat(ev.getExtras()).containsEntry("telefone", "[REDACTED]");
		assertThat(ev.getTags()).containsEntry("quem", "[EMAIL_REDACTED]");
		assertThat(bc.getMessage()).isEqualTo("ligou [PHONE_REDACTED]");
		assertThat(bc.getData()).containsEntry("senha", "[REDACTED]");
		assertThat(ex.getValue()).isEqualTo("falhou para [EMAIL_REDACTED]");
	}

	@Test
	void falhaDefinitivaDeJobVaiProSentry() {
		BancoDeTeste.exigirContainer(jdbc);
		TRANSPORT.eventos.clear();
		tx.executeWithoutResult(s -> fila.enfileirar(jobQueSempreFalha, new Falha()));
		await().atMost(Duration.ofSeconds(10)).until(() -> !TRANSPORT.eventos.isEmpty());
		assertThat(TRANSPORT.eventos).hasSize(1);
		SentryEvent ev = TRANSPORT.eventos.get(0);
		assertThat(ev.getExceptions().get(0).getValue()).isEqualTo("nao enviou para [EMAIL_REDACTED]");
		assertThat(ev.getTag("job")).isEqualTo("job-que-falha");
	}
}
