package me.clama.conta;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import me.clama.BancoDeTeste;
import me.clama.conta.interno.Papel;
import me.clama.conta.interno.Usuario;
import me.clama.conta.interno.UsuarioRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(BancoDeTeste.class)
class ContaApiTest {

	@Autowired ContaApi api;
	@Autowired UsuarioRepository usuarios;
	@Autowired JdbcTemplate jdbc;

	@AfterEach
	void limpar() {
		jdbc.update("delete from usuario where email like 'resumos-%'");
	}

	@Test
	void resumosTrazNomePublicoEPapelEIgnoraIdInexistente() {
		var ana = usuarios.save(Usuario.novo("resumos-ana@exemplo.com", "Ana Maria de Souza", Papel.CLIENTE, Instant.now()));
		var adm = usuarios.save(Usuario.novo("resumos-adm@exemplo.com", "", Papel.ADMIN, Instant.now()));
		var sup = usuarios.save(Usuario.novo("resumos-sup@exemplo.com", "Zé", Papel.SUPERUSUARIO, Instant.now()));
		var mapa = api.resumos(List.of(ana.getId(), adm.getId(), sup.getId(), -1L));
		assertThat(mapa).containsOnlyKeys(ana.getId(), adm.getId(), sup.getId());
		assertThat(mapa.get(ana.getId()).nomePublico()).isEqualTo("Ana S.");
		assertThat(mapa.get(ana.getId()).admin()).isFalse();
		assertThat(mapa.get(ana.getId()).email()).isEqualTo("resumos-ana@exemplo.com");
		assertThat(mapa.get(adm.getId()).nomePublico()).isEqualTo("Pedro");
		assertThat(mapa.get(adm.getId()).admin()).isTrue();
		assertThat(mapa.get(sup.getId()).admin()).isTrue();
	}

	@Test
	void semIdsDevolveMapaVazio() {
		assertThat(api.resumos(List.of())).isEmpty();
	}
}
