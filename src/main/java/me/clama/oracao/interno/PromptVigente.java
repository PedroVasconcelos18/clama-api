package me.clama.oracao.interno;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.json.JsonMapper;

/**
 * O prompt que a geracao usa agora (D15): a versao ativa do admin ou, sem nenhuma, o padrao do codigo
 * ({@code resources/oracao/prompt-padrao.yml}, lido uma vez no boot; recurso quebrado derruba o boot). Nunca falha por
 * "sem prompt ativo": o indice parcial unico garante no maximo uma ativa.
 */
@Component
public class PromptVigente {

	static final String RECURSO = "oracao/prompt-padrao.yml";
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** {@code versaoId} nulo = o padrao do codigo. */
	public record Vigente(@Nullable Long versaoId, Prompt prompt) {}

	private final JdbcClient jdbc;
	private final Prompt padrao;

	PromptVigente(JdbcClient jdbc) {
		this.jdbc = jdbc;
		this.padrao = carregarPadrao();
	}

	public Vigente vigente() {
		return jdbc.sql("select id, conteudo from prompt_versao where ativo")
				.query((rs, i) -> new Vigente(rs.getLong("id"), ler(rs.getString("conteudo")))).optional()
				.orElseGet(() -> new Vigente(null, padrao));
	}

	public Prompt padrao() {
		return padrao;
	}

	static Prompt ler(String json) {
		return JSON.readValue(json, Prompt.class);
	}

	static String json(Prompt p) {
		return JSON.writeValueAsString(p);
	}

	private static Prompt carregarPadrao() {
		Map<String, String> m;
		try (var in = new ClassPathResource(RECURSO).getInputStream()) {
			m = new Yaml().load(in);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		var p = new Prompt(m.get("sistema"), m.get("simples"), m.get("comVersiculo"), m.get("comProfeciaEVersiculos"), m.get("gratuito"));
		if (Stream.of(p.sistema(), p.simples(), p.comVersiculo(), p.comProfeciaEVersiculos(), p.gratuito())
				.anyMatch(t -> t == null || t.isBlank())) {
			throw new IllegalStateException(RECURSO + " sem alguma das cinco partes");
		}
		return p;
	}
}
