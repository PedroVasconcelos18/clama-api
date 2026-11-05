package me.clama.api.comum.observabilidade;

import io.sentry.Hint;
import io.sentry.ITransportFactory;
import io.sentry.SentryEnvelope;
import io.sentry.SentryEnvelopeItem;
import io.sentry.SentryEvent;
import io.sentry.SentryItemType;
import io.sentry.Sentry;
import io.sentry.transport.ITransport;
import io.sentry.transport.RateLimiter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Transport falso: guarda os eventos em memoria e nunca toca a rede. */
public class TransportEmMemoria implements ITransport {
	public final List<SentryEvent> eventos = new CopyOnWriteArrayList<>();

	public ITransportFactory fabrica() {
		return (options, details) -> this;
	}

	@Override
	public void send(SentryEnvelope envelope, Hint hint) {
		for (SentryEnvelopeItem item : envelope.getItems()) {
			if (item.getHeader().getType() == SentryItemType.Event) {
				try {
					eventos.add(item.getEvent(Sentry.getCurrentScopes().getOptions().getSerializer()));
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
			}
		}
	}

	@Override public void flush(long timeoutMillis) {}
	@Override public RateLimiter getRateLimiter() { return null; }
	@Override public void close(boolean isRestarting) {}
	@Override public void close() {}
}
