package me.clama.conta.interno;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import me.clama.infra.web.ErroDeNegocio;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** O hash de senha (argon2, caro em CPU e memoria) roda no maximo 4 por vez, para rajada nao esgotar o heap. */
@Component
public class VagasDeHash {

	static final String PASTORAL_OCUPADO = "Muita gente entrando agora — tente de novo em instantes.";

	private final Semaphore vagas = new Semaphore(4);

	/** Roda o trabalho com uma vaga; sem vaga em 5 s, 503. Nao chamar dentro de transacao. */
	public <T> T comVaga(Supplier<T> trabalho) {
		try {
			if (!vagas.tryAcquire(5, TimeUnit.SECONDS)) throw ocupado();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw ocupado();
		}
		try {
			return trabalho.get();
		} finally {
			vagas.release();
		}
	}

	private static ErroDeNegocio ocupado() {
		return new ErroDeNegocio(HttpStatus.SERVICE_UNAVAILABLE, "ocupado", "Servidor ocupado.", PASTORAL_OCUPADO);
	}
}
