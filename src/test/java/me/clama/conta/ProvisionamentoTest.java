package me.clama.conta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.UUID;
import me.clama.BancoDeTeste;
import me.clama.CaixaDeSaidaEmMemoria;
import me.clama.EmailDeTeste;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import({BancoDeTeste.class, EmailDeTeste.class})
class ProvisionamentoTest {

	@Autowired ContaApi api;
	@Autowired UsuarioRepository usuarios;
	@Autowired PasswordEncoder encoder;
	@Autowired TransactionTemplate transacao;
	@Autowired CaixaDeSaidaEmMemoria caixa;

	@BeforeEach
	void zerar() {
		caixa.limpar();
	}

	private static String email() {
		return "doador-" + UUID.randomUUID() + "@exemplo.com";
	}

	@Test
	void criaClienteSemSenhaEEnviaLinkDepoisDoCommit() {
		var email = email();
		var resumo = transacao.execute(s -> {
			var r = api.provisionarOuVincular(email, "Maria da Graça");
			assertThat(caixa.enviados()).as("nada sai antes do commit").isEmpty();
			return r;
		});
		assertThat(resumo.email()).isEqualTo(email);
		assertThat(resumo.nomeCompleto()).isEqualTo("Maria da Graça");
		var u = usuarios.findById(resumo.id()).orElseThrow();
		assertThat(u.getPapel()).isEqualTo(Papel.CLIENTE);
		assertThat(u.temSenha()).isFalse();
		assertThat(u.isAtivo()).isTrue();
		assertThat(caixa.para(email)).hasSize(1);
		var texto = caixa.para(email).get(0).email().texto();
		assertThat(texto).contains("Maria").contains("/definir-senha?token=");
	}

	@Test
	void vinculaExistentePeloCanonicoSemEmail() {
		var local = "alguem" + System.nanoTime();
		usuarios.save(Usuario.novo(local + "@gmail.com", "Alguem", Papel.CLIENTE, java.time.Instant.now()));
		var resumo = transacao.execute(s -> api.provisionarOuVincular(local.substring(0, 3) + "." + local.substring(3) + "+doacao@Gmail.com", "Outro Nome"));
		assertThat(resumo.email()).isEqualTo(local + "@gmail.com");
		assertThat(resumo.nomeCompleto()).isEqualTo("Alguem");
		assertThat(caixa.enviados()).isEmpty();
	}

	@Test
	void rollbackNaoEnvia() {
		var email = email();
		assertThatThrownBy(() -> transacao.executeWithoutResult(s -> {
			api.provisionarOuVincular(email, "Fulana");
			throw new IllegalStateException("estourou");
		})).isInstanceOf(IllegalStateException.class);
		assertThat(caixa.enviados()).isEmpty();
		assertThat(usuarios.findByEmail(email)).isEmpty();
	}

	@Test
	void exigeTransacaoDoChamador() {
		assertThatThrownBy(() -> api.provisionarOuVincular(email(), "x")).isInstanceOf(IllegalTransactionStateException.class);
	}

	@Test
	void doisProvisionamentosSimultaneosMesmoEmailUmaConta() throws Exception {
		var email = email();
		var largada = new CountDownLatch(1);
		var pool = Executors.newFixedThreadPool(2);
		try {
			var futuros = new ArrayList<java.util.concurrent.Future<UsuarioResumo>>();
			for (int i = 0; i < 2; i++) {
				futuros.add(pool.submit(() -> {
					largada.await();
					return transacao.execute(s -> {
						var r = api.provisionarOuVincular(email, "Concorrente");
						try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
						return r; // segura a transacao aberta para o outro bater no unique
					});
				}));
			}
			largada.countDown();
			var a = futuros.get(0).get();
			var b = futuros.get(1).get();
			assertThat(a.id()).isEqualTo(b.id());
		} finally {
			pool.shutdownNow();
		}
		assertThat(usuarios.findByEmailCanonico(email)).isPresent();
		assertThat(caixa.para(email)).as("so quem criou envia").hasSize(1);
	}
}
