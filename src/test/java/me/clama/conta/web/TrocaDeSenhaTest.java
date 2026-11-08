package me.clama.conta.web;

import static me.clama.conta.web.Navegador.emailUnico;
import static me.clama.conta.web.Navegador.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import me.clama.BancoDeTeste;
import me.clama.conta.interno.Papel;
import me.clama.conta.interno.UsuarioRepository;
import me.clama.infra.seguranca.LimiteDeTaxa;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(BancoDeTeste.class)
class TrocaDeSenhaTest {

	static final String ATUAL = "senha-forte-123";

	@LocalServerPort int porta;
	@Autowired UsuarioRepository usuarios;
	@Autowired PasswordEncoder encoder;
	@Autowired LimiteDeTaxa limite;

	@BeforeEach
	void zerarLimites() {
		limite.limpar();
	}

	private Navegador entrar(String email) throws Exception {
		var nav = new Navegador(porta);
		assertThat(nav.login(email, ATUAL).statusCode()).isEqualTo(200);
		return nav;
	}

	private String email() {
		var email = emailUnico();
		Navegador.usuario(usuarios, encoder, email, ATUAL, Papel.CLIENTE);
		return email;
	}

	private static String corpo(String atual, String nova) {
		return "{\"senhaAtual\":\"" + atual + "\",\"novaSenha\":\"" + nova + "\"}";
	}

	@Test
	void trocaEPermiteEntrarComANova() throws Exception {
		var email = email();
		var res = entrar(email).post("/api/conta/senha", corpo(ATUAL, "outra-senha-forte-9"));
		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(json(res).get("mensagemPastoral").asString()).isEqualTo("Senha atualizada — você já pode seguir em paz.");
		assertThat(new Navegador(porta).login(email, ATUAL).statusCode()).isEqualTo(401);
		assertThat(new Navegador(porta).login(email, "outra-senha-forte-9").statusCode()).isEqualTo(200);
	}

	@Test
	void senhaAtualErrada() throws Exception {
		var res = entrar(email()).post("/api/conta/senha", corpo("errada-errada", "outra-senha-forte-9"));
		assertThat(res.statusCode()).isEqualTo(400);
		assertThat(json(res).get("codigo").asString()).isEqualTo("senha_atual_invalida");
		assertThat(json(res).get("detail").asString()).isEqualTo("A senha atual está incorreta.");
	}

	@Test
	void senhaNovaFracaTrazAsMensagensDoValidador() throws Exception {
		var res = entrar(email()).post("/api/conta/senha", corpo(ATUAL, "12345"));
		assertThat(res.statusCode()).isEqualTo(400);
		var corpo = json(res);
		assertThat(corpo.get("codigo").asString()).isEqualTo("senha_fraca");
		assertThat(corpo.get("erros").get(0).get("campo").asString()).isEqualTo("novaSenha");
		var mensagens = corpo.get("erros").valueStream().map(e -> e.get("mensagem").asString()).toList();
		assertThat(mensagens).contains("Esta senha é muito curta. Ela precisa conter pelo menos 8 caracteres.",
				"Esta senha é inteiramente numérica.");
	}

	@Test
	void senhaParecidaComOEmailEhFraca() throws Exception {
		var email = email();
		var res = entrar(email).post("/api/conta/senha", corpo(ATUAL, email));
		assertThat(json(res).get("codigo").asString()).isEqualTo("senha_fraca");
	}

	@Test
	void entradaGrandeDemaisEh400() throws Exception {
		var res = entrar(email()).post("/api/conta/senha", corpo(ATUAL, "a".repeat(1025)));
		assertThat(res.statusCode()).isEqualTo(400);
	}

	@Test
	void outrasSessoesCaemACorrenteFica() throws Exception {
		var email = email();
		var a = entrar(email);
		var b = entrar(email);
		assertThat(a.post("/api/conta/senha", corpo(ATUAL, "outra-senha-forte-9")).statusCode()).isEqualTo(200);
		assertThat(a.get("/api/auth/eu").statusCode()).isEqualTo(200);
		assertThat(b.get("/api/auth/eu").statusCode()).isEqualTo(401);
	}

	@Test
	void duasTrocasSimultaneasSoUmaPassa() throws Exception {
		var nav = entrar(email());
		var largada = new CountDownLatch(1);
		var pool = Executors.newFixedThreadPool(2);
		try {
			Callable<Integer> troca1 = () -> {
				largada.await();
				return nav.post("/api/conta/senha", corpo(ATUAL, "nova-senha-forte-1")).statusCode();
			};
			Callable<Integer> troca2 = () -> {
				largada.await();
				return nav.post("/api/conta/senha", corpo(ATUAL, "nova-senha-forte-2")).statusCode();
			};
			var f1 = pool.submit(troca1);
			var f2 = pool.submit(troca2);
			largada.countDown();
			assertThat(java.util.List.of(f1.get(), f2.get())).containsExactlyInAnyOrder(200, 409);
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void limiteDeDezPorHora() throws Exception {
		var nav = entrar(email());
		for (int i = 0; i < 10; i++) {
			assertThat(nav.post("/api/conta/senha", corpo("errada-errada", "outra-senha-forte-9")).statusCode())
					.isEqualTo(400);
		}
		assertThat(nav.post("/api/conta/senha", corpo("errada-errada", "outra-senha-forte-9")).statusCode())
				.isEqualTo(429);
	}
}
