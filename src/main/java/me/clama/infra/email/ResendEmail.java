package me.clama.infra.email;

import com.resend.core.exception.ResendException;
import com.resend.core.net.RequestOptions;
import com.resend.services.emails.Emails;
import com.resend.services.emails.model.CreateEmailOptions;
import java.time.Duration;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adaptador da Resend (SDK oficial). A chave de idempotencia vai no header {@code Idempotency-Key}. "Ja enviado"
 * (409 invalid_idempotent_request) e sucesso; 5xx, 429 e erro de rede tentam ate 3 vezes (1 s e 2 s de espera);
 * os demais 4xx nao. Cada chamada tem prazo de 10 s.
 */
// ponytail: laco simples em vez de @Retryable: a regra "5xx sim, 4xx e ja-enviado nao" e o teste sem dormir ficam
// triviais sem proxy nem contexto Spring.
public class ResendEmail implements EnviadorDeEmail {

	private static final Logger LOG = LoggerFactory.getLogger(ResendEmail.class);
	static final String RESPONDER_PARA = "contato@clama.me";
	private static final int TENTATIVAS = 3;

	private final Emails emails; // nulo = sem RESEND_API_KEY
	private final String remetente;
	private final Consumer<Duration> dormir;

	public ResendEmail(Emails emails, String remetente, Consumer<Duration> dormir) {
		this.emails = emails;
		this.remetente = remetente;
		this.dormir = dormir;
	}

	@Override
	public void enviar(Email email, String chaveIdempotencia) {
		if (emails == null) {
			throw new IllegalStateException("RESEND_API_KEY ausente: configure clama.email.resend-api-key para enviar e-mail");
		}
		var opcoes = CreateEmailOptions.builder().from(remetente).to(email.para()).subject(email.assunto())
				.text(email.texto()).html(email.html()).replyTo(RESPONDER_PARA).build();
		var requisicao = RequestOptions.builder().setIdempotencyKey(chaveIdempotencia)
				.timeout(Duration.ofSeconds(10)).build();
		for (int tentativa = 1;; tentativa++) {
			try {
				emails.send(opcoes, requisicao);
				return;
			} catch (RuntimeException e) { // o SDK pode embrulhar falha de rede; so ela e retentada
				if (!causadoPorRede(e) || tentativa == TENTATIVAS) throw e;
				dormir.accept(Duration.ofSeconds(1L << (tentativa - 1)));
			} catch (ResendException e) {
				if (jaEnviado(e)) {
					LOG.warn("Resend: e-mail ja enviado (chave {})", chaveIdempotencia);
					return;
				}
				var status = e.getStatusCode();
				boolean retentavel = status == null || status >= 500 || status == 429; // sem status = falha de rede
				if (!retentavel || tentativa == TENTATIVAS) {
					throw new IllegalStateException("Resend recusou o e-mail (status " + status + ")", e);
				}
				dormir.accept(Duration.ofSeconds(1L << (tentativa - 1)));
			}
		}
	}

	private static boolean causadoPorRede(Throwable e) {
		for (var c = e; c != null; c = c.getCause()) if (c instanceof java.io.IOException) return true;
		return false;
	}

	private static boolean jaEnviado(ResendException e) {
		return Integer.valueOf(409).equals(e.getStatusCode()) && ("invalid_idempotent_request".equals(e.getErrorName())
				|| (e.getResponseBody() != null && e.getResponseBody().contains("invalid_idempotent_request")));
	}

	static void dormir(Duration d) {
		try {
			Thread.sleep(d);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
