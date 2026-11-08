package me.clama.conta.web;

import static me.clama.conta.web.Navegador.emailUnico;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import me.clama.BancoDeTeste;
import me.clama.conta.Papel;
import me.clama.conta.UsuarioRepository;
import me.clama.infra.seguranca.LimiteDeTaxa;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(BancoDeTeste.class)
class UltimoSuperusuarioConcorrenteTest {

	@LocalServerPort int porta;
	@Autowired UsuarioRepository usuarios;
	@Autowired PasswordEncoder encoder;
	@Autowired LimiteDeTaxa limite;
	@Autowired JdbcClient jdbc;

	@Test
	void doisRebaixamentosSimultaneosDosDoisUnicosSoUmPassa() throws Exception {
		limite.limpar();
		jdbc.sql("delete from usuario where papel = 'SUPERUSUARIO'").update();
		for (int rodada = 0; rodada < 5; rodada++) {
			var a = Navegador.usuario(usuarios, encoder, emailUnico(), "senha-forte-123", Papel.SUPERUSUARIO);
			var b = Navegador.usuario(usuarios, encoder, emailUnico(), "senha-forte-123", Papel.SUPERUSUARIO);
			var navA = new Navegador(porta);
			var navB = new Navegador(porta);
			assertThat(navA.login(a.getEmail(), "senha-forte-123").statusCode()).isEqualTo(200);
			assertThat(navB.login(b.getEmail(), "senha-forte-123").statusCode()).isEqualTo(200);
			var largada = new CountDownLatch(1);
			var pool = Executors.newFixedThreadPool(2);
			try {
				Callable<Integer> aRebaixaB = () -> {
					largada.await();
					return navA.patch("/api/admin/usuarios/" + b.getId(), "{\"papel\":\"ADMIN\"}").statusCode();
				};
				Callable<Integer> bRebaixaA = () -> {
					largada.await();
					return navB.patch("/api/admin/usuarios/" + a.getId(), "{\"papel\":\"ADMIN\"}").statusCode();
				};
				var f1 = pool.submit(aRebaixaB);
				var f2 = pool.submit(bRebaixaA);
				largada.countDown();
				assertThat(List.of(f1.get(), f2.get())).containsExactlyInAnyOrder(200, 409);
			} finally {
				pool.shutdownNow();
			}
			assertThat(usuarios.countByPapelAndAtivoTrue(Papel.SUPERUSUARIO)).isEqualTo(1);
			jdbc.sql("delete from usuario where papel = 'SUPERUSUARIO'").update();
		}
	}
}
