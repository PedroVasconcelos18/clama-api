package me.clama.freemium.interno;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import me.clama.pedido.NovoPedido;
import org.springframework.jdbc.core.JdbcTemplate;

/** Montagem comum dos testes da confirmacao: pedido, link e plano gratuito. */
final class FreemiumDeTeste {

	private FreemiumDeTeste() {}

	static NovoPedido novo(String email, String cpf) {
		return new NovoPedido("Maria Souza", email, cpf, null, null, null, "Pela família", null, null, null, true, null, null);
	}

	/** O plano gratuito pode ter sido apagado por outro teste. */
	static void planoGratuito(JdbcTemplate jdbc) {
		jdbc.update("""
				insert into plano (nome, descricao, valor_centavos, complexidade, tipo, ordem)
				values ('Gratuito', 'Sua primeira oração — por nossa conta', 0, 'SIMPLES', 'GRATUITO', 1000)
				on conflict (tipo) where tipo <> 'COMUM' do nothing""");
	}

	/** Grava um link (so o hash) para o pedido e devolve o token em claro. */
	static String link(JdbcTemplate jdbc, UUID pedido, Instant expiraEm) {
		var token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
		jdbc.update("insert into freemium_confirmacao (pedido_id, hash, criado_em, expira_em) values (?, ?, now(), ?)",
				pedido, Confirmacoes.hash(token), OffsetDateTime.ofInstant(expiraEm, ZoneOffset.UTC));
		return token;
	}
}
