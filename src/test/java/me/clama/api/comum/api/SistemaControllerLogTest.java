package me.clama.api.comum.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.csrf.CsrfTokenRepository;

class SistemaControllerLogTest {
	@Test
	void healthLogaFalhaDoBanco() {
		var jdbc = mock(JdbcTemplate.class);
		when(jdbc.queryForObject(eq("SELECT 1"), eq(Integer.class)))
				.thenThrow(new DataAccessResourceFailureException("conexao recusada"));
		var logger = (Logger) LoggerFactory.getLogger(SistemaController.class);
		var appender = new ListAppender<ILoggingEvent>();
		appender.start();
		logger.addAppender(appender);
		try {
			var corpo = new SistemaController(jdbc, mock(CsrfTokenRepository.class)).health();
			assertThat(corpo).containsEntry("database", "error").containsEntry("status", "ok");
			assertThat(appender.list).singleElement().satisfies(e -> {
				assertThat(e.getLevel()).isEqualTo(Level.WARN);
				assertThat(e.getThrowableProxy().getMessage()).contains("conexao recusada");
			});
		} finally {
			logger.detachAppender(appender);
		}
	}
}
