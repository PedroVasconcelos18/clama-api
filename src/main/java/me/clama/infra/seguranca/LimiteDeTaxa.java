package me.clama.infra.seguranca;

import io.github.bucket4j.Bucket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import me.clama.infra.web.ErroDeNegocio;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Limite de tentativas por chave pronta ("login:email:x", "login:ip:y"); quem chama monta as chaves. Bucket4j com
 * recarga gradual: estoura o limite e cada ficha volta em janela/limite (o mais perto da janela deslizante).
 * Memoria limitada: a chave guardada e o SHA-256 (tamanho fixo) e ha um teto de baldes, despejando o menos usado.
 */
// ponytail: em memoria, so vale com uma instancia; Bucket4j sobre o Postgres se o Railway passar de uma replica.
// Quem inunda o mapa com TETO chaves novas despeja baldes antigos; com o hash limitado a 4 por vez isso leva minutos
@Component
public class LimiteDeTaxa {

	public static final String CODIGO = "limite_excedido";
	public static final String PASTORAL =
			"Você fez vários pedidos seguidos — espera um instante e tenta de novo, com calma.";
	private static final int TETO = 50_000; // ~poucos MB: 64 caracteres de chave + um balde por entrada

	public record Cota(String chave, int limite, Duration janela) {}

	private final Map<String, Bucket> baldes;

	public LimiteDeTaxa() {
		this(TETO);
	}

	LimiteDeTaxa(int teto) {
		baldes = new LinkedHashMap<>(16, 0.75f, true) { // ordem de acesso: despeja o menos usado
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, Bucket> maisAntigo) {
				return size() > teto;
			}
		};
	}

	/** Confere todas as cotas antes de gastar: estourar uma nao consome as outras. Estourou, 429. */
	public synchronized void exigir(Cota... cotas) {
		var usados = Arrays.stream(cotas).map(c -> baldes.computeIfAbsent(sha256(c.chave()), k -> novo(c))).toList();
		if (usados.stream().anyMatch(b -> b.getAvailableTokens() < 1)) {
			throw new ErroDeNegocio(HttpStatus.TOO_MANY_REQUESTS, CODIGO, "Limite de tentativas excedido.", PASTORAL);
		}
		usados.forEach(b -> b.tryConsume(1));
	}

	/** Zera tudo (testes). */
	public synchronized void limpar() {
		baldes.clear();
	}

	synchronized int tamanho() {
		return baldes.size();
	}

	private static Bucket novo(Cota c) {
		return Bucket.builder().addLimit(l -> l.capacity(c.limite()).refillGreedy(c.limite(), c.janela())).build();
	}

	private static String sha256(String chave) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(chave.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e); // todo JDK traz SHA-256
		}
	}
}
