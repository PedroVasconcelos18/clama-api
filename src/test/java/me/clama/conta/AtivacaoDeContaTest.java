package me.clama.conta;

import static me.clama.conta.web.Navegador.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import me.clama.BancoDeTeste;
import me.clama.EmailDeTeste;
import me.clama.conta.interno.Papel;
import me.clama.conta.interno.UsuarioRepository;
import me.clama.conta.web.Navegador;
import me.clama.infra.seguranca.LimiteDeTaxa;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Review Focus 2: o link de ativacao nao revoga o anterior; definir a senha mata todos. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import({BancoDeTeste.class, EmailDeTeste.class})
class AtivacaoDeContaTest {

	static final String NOVA = "outra-senha-forte-9";

	@LocalServerPort int porta;
	@Autowired ContaApi conta;
	@Autowired UsuarioRepository usuarios;
	@Autowired PasswordEncoder encoder;
	@Autowired LimiteDeTaxa limite;
	@Autowired JdbcTemplate jdbc;

	@BeforeEach
	@AfterEach
	void limpar() {
		limite.limpar();
		jdbc.update("delete from usuario where email like 'ativ-%'");
	}

	private Long usuario(String senha) {
		return Navegador.usuario(usuarios, encoder, "ativ-" + UUID.randomUUID() + "@exemplo.com", senha, Papel.CLIENTE).getId();
	}

	private String email(Long id) {
		return usuarios.findById(id).orElseThrow().getEmail();
	}

	private HttpResponse<String> definir(String token) throws Exception {
		var nav = new Navegador(porta);
		nav.get("/api/auth/eu");
		return nav.post("/api/auth/redefinicao-de-senha", Navegador.JSON.writeValueAsString(Map.of("token", token, "novaSenha", NOVA)));
	}

	@Test
	void contaComSenhaNaoTemAtivacao() {
		var comSenha = usuario("senha-forte-123");
		assertThat(conta.tokenDeAtivacao(comSenha, email(comSenha))).isEmpty();
		assertThat(conta.linkDeAtivacao(comSenha, email(comSenha))).isEmpty();

		var inativa = usuarios.findById(usuario(null)).orElseThrow();
		inativa.desativar();
		usuarios.save(inativa);
		assertThat(conta.tokenDeAtivacao(inativa.getId(), inativa.getEmail())).isEmpty();
		assertThat(conta.linkDeAtivacao(inativa.getId(), inativa.getEmail())).isEmpty();

		var semSenha = usuario(null);
		assertThat(conta.tokenDeAtivacao(semSenha, email(semSenha))).hasValueSatisfying(t -> assertThat(t).matches("[\\w-]{43}"));
		assertThat(conta.linkDeAtivacao(semSenha, email(semSenha))).hasValueSatisfying(l -> assertThat(l).matches("http://localhost:5173/definir-senha#token=[\\w-]{43}"));
		assertThat(conta.tokenDeAtivacao(-1L, "ninguem@exemplo.com")).isEmpty();
	}

	/** O link so vai para a caixa da propria conta: e-mail do pedido corrigido para outro endereco nao recebe token. */
	@Test
	void ativacaoSoParaOEmailDaConta() {
		var local = "ativ-" + System.nanoTime();
		var id = Navegador.usuario(usuarios, encoder, local + "@gmail.com", null, Papel.CLIENTE).getId();
		assertThat(conta.tokenDeAtivacao(id, "outra-pessoa@exemplo.com")).isEmpty();
		assertThat(conta.linkDeAtivacao(id, "outra-pessoa@exemplo.com")).isEmpty();
		assertThat(conta.tokenDeAtivacao(id, null)).isEmpty();
		var variante = local.substring(0, 6) + "." + local.substring(6) + "+oracao@GMail.com"; // mesmo canonico
		assertThat(conta.tokenDeAtivacao(id, variante)).isPresent();
		assertThat(conta.linkDeAtivacao(id, local + "@gmail.com")).isPresent();
	}

	@Test
	void tokenDaTelaSobreviveAoLinkDoEmailDaOracao() throws Exception {
		var id = usuario(null);
		var a = conta.tokenDeAtivacao(id, email(id)).orElseThrow(); // a tela da confirmacao
		var b = conta.linkDeAtivacao(id, email(id)).orElseThrow().replaceFirst(".*#token=", ""); // o e-mail da oracao, segundos depois
		assertThat(b).isNotEqualTo(a);

		var res = definir(a);
		assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
		assertThat(new Navegador(porta).login(usuarios.findById(id).orElseThrow().getEmail(), NOVA).statusCode()).isEqualTo(200);

		var depois = definir(b);
		assertThat(depois.statusCode()).isEqualTo(400);
		assertThat(json(depois).get("codigo").asString()).isEqualTo("link_invalido");
		assertThat(conta.tokenDeAtivacao(id, email(id))).as("com senha, nao ha mais ativacao").isEmpty();
	}
}
