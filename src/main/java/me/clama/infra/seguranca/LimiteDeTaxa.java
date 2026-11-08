package me.clama.infra.seguranca;

import io.github.bucket4j.Bucket;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import me.clama.infra.web.ErroDeNegocio;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Limite de tentativas por chave pronta ("login:email:x", "login:ip:y"); quem chama monta as chaves. Bucket4j com
 * recarga gradual: estoura o limite e cada ficha volta em janela/limite (o mais perto da janela deslizante).
 */
// ponytail: em memoria, so vale com uma instancia; Bucket4j sobre o Postgres se o Railway passar de uma replica
@Component
public class LimiteDeTaxa {

	public static final String CODIGO = "limite_excedido";
	public static final String PASTORAL =
			"Você fez vários pedidos seguidos — espera um instante e tenta de novo, com calma.";
	private static final int FAXINA_ACIMA_DE = 10_000;

	public record Cota(String chave, int limite, Duration janela) {}

	private record Balde(Bucket bucket, int limite) {}

	private final ConcurrentHashMap<String, Balde> baldes = new ConcurrentHashMap<>();

	/** Confere todas as cotas antes de gastar: estourar uma nao consome as outras. Estourou, 429. */
	public synchronized void exigir(Cota... cotas) {
		if (baldes.size() > FAXINA_ACIMA_DE) { // balde cheio e igual a um novo: descartar nao muda nada
			baldes.values().removeIf(b -> b.bucket().getAvailableTokens() >= b.limite());
		}
		var usados = Arrays.stream(cotas).map(c -> baldes.computeIfAbsent(c.chave(), k -> novo(c))).toList();
		if (usados.stream().anyMatch(b -> b.bucket().getAvailableTokens() < 1)) {
			throw new ErroDeNegocio(HttpStatus.TOO_MANY_REQUESTS, CODIGO, "Limite de tentativas excedido.", PASTORAL);
		}
		usados.forEach(b -> b.bucket().tryConsume(1));
	}

	/** Zera tudo (testes). */
	public void limpar() {
		baldes.clear();
	}

	private static Balde novo(Cota c) {
		return new Balde(Bucket.builder()
				.addLimit(l -> l.capacity(c.limite()).refillGreedy(c.limite(), c.janela())).build(), c.limite());
	}
}
