package me.clama.conta.web;

import static me.clama.conta.web.Navegador.emailUnico;
import static me.clama.conta.web.Navegador.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;
import me.clama.BancoDeTeste;
import me.clama.CaixaDeSaidaEmMemoria;
import me.clama.EmailDeTeste;
import me.clama.EmailDeTeste.RelogioAvancavel;
import me.clama.conta.ContaApi;
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
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import({BancoDeTeste.class, EmailDeTeste.class})
class RedefinicaoTest {

	static final String ANTIGA = "senha-forte-123";
	static final String NOVA = "outra-senha-forte-9";

	@LocalServerPort int porta;
	@Autowired UsuarioRepository usuarios;
	@Autowired PasswordEncoder encoder;
	@Autowired LimiteDeTaxa limite;
	@Autowired CaixaDeSaidaEmMemoria caixa;
	@Autowired RelogioAvancavel relogio;
	@Autowired ContaApi contaApi;
	@Autowired TransactionTemplate transacao;

	@BeforeEach
	void zerar() {
		limite.limpar();
		caixa.limpar();
		relogio.zerar();
	}

	private String pedirToken(String email) throws Exception {
		var nav = new Navegador(porta);
		nav.get("/api/auth/eu");
		var antes = caixa.para(email).size();
		assertThat(nav.post("/api/auth/esqueci-senha", "{\"email\":\"" + email + "\"}").statusCode()).isEqualTo(200);
		caixa.aguardarPara(email, antes + 1); // o envio e assincrono
		return ultimoToken(email);
	}

	private String ultimoToken(String email) {
		var enviados = caixa.para(email);
		var m = Pattern.compile("token=([\\w-]+)").matcher(enviados.get(enviados.size() - 1).email().texto());
		assertThat(m.find()).isTrue();
		return m.group(1);
	}

	private HttpResponse<String> redefinir(String token, String senha) throws Exception {
		var nav = new Navegador(porta);
		nav.get("/api/auth/eu");
		return nav.post("/api/auth/redefinicao-de-senha",
				Navegador.JSON.writeValueAsString(Map.of("token", token, "novaSenha", senha)));
	}

	private String conta() {
		var email = emailUnico();
		Navegador.usuario(usuarios, encoder, email, ANTIGA, Papel.CLIENTE);
		return email;
	}

	private void assertLinkInvalido(HttpResponse<String> res) {
		assertThat(res.statusCode()).isEqualTo(400);
		assertThat(json(res).get("codigo").asString()).isEqualTo("link_invalido");
		assertThat(json(res).get("mensagemPastoral").asString()).isEqualTo("Este link não vale mais. Peça um novo.");
	}

	@Test
	void usoUnico() throws Exception {
		var email = conta();
		var token = pedirToken(email);
		assertThat(redefinir(token, NOVA).statusCode()).isEqualTo(200);
		assertThat(new Navegador(porta).login(email, NOVA).statusCode()).isEqualTo(200);
		assertThat(new Navegador(porta).login(email, ANTIGA).statusCode()).isEqualTo(401);
		assertLinkInvalido(redefinir(token, "mais-uma-senha-forte-7"));
	}

	@Test
	void tokenInexistenteEInvalido() throws Exception {
		assertLinkInvalido(redefinir("nao-existe", NOVA));
	}

	@Test
	void expira() throws Exception {
		var email = conta();
		var token = pedirToken(email);
		relogio.avancar(Duration.ofMinutes(61));
		assertLinkInvalido(redefinir(token, NOVA));
		assertThat(new Navegador(porta).login(email, ANTIGA).statusCode()).isEqualTo(200);
	}

	@Test
	void pedirNovoInvalidaAnterior() throws Exception {
		var email = conta();
		var primeiro = pedirToken(email);
		var segundo = pedirToken(email);
		assertThat(segundo).isNotEqualTo(primeiro);
		assertLinkInvalido(redefinir(primeiro, NOVA));
		assertThat(redefinir(segundo, NOVA).statusCode()).isEqualTo(200);
	}

	@Test
	void derrubaSessoes() throws Exception {
		var email = conta();
		var logado = new Navegador(porta);
		assertThat(logado.login(email, ANTIGA).statusCode()).isEqualTo(200);
		assertThat(logado.get("/api/auth/eu").statusCode()).isEqualTo(200);
		assertThat(redefinir(pedirToken(email), NOVA).statusCode()).isEqualTo(200);
		assertThat(logado.get("/api/auth/eu").statusCode()).isEqualTo(401);
	}

	@Test
	void senhaFracaDa400ENaoGastaOToken() throws Exception {
		var email = conta();
		var token = pedirToken(email);
		var res = redefinir(token, "12345");
		assertThat(res.statusCode()).isEqualTo(400);
		assertThat(json(res).get("codigo").asString()).isEqualTo("senha_fraca");
		assertThat(json(res).get("erros").get(0).get("campo").asString()).isEqualTo("novaSenha");
		assertThat(redefinir(token, NOVA).statusCode()).isEqualTo(200);
	}

	@Test
	void definirSenhaDeContaNova() throws Exception {
		var email = emailUnico();
		var id = transacao.execute(s -> contaApi.provisionarOuVincular(email, "Maria da Graça")).id();
		assertThat(usuarios.findByEmail(email).orElseThrow().temSenha()).isFalse();
		assertThat(new Navegador(porta).login(email, ANTIGA).statusCode()).isEqualTo(401);

		var link = contaApi.linkDeAtivacao(id, email).orElseThrow(); // vai no e-mail da oracao
		assertThat(link).startsWith("http://localhost:5173/definir-senha#token=");
		assertThat(redefinir(link.replaceFirst(".*#token=", ""), NOVA).statusCode()).isEqualTo(200);
		assertThat(new Navegador(porta).login(email, NOVA).statusCode()).isEqualTo(200);
	}

	@Test
	void redefinirRevogaOutrosLinksPendentes() throws Exception {
		var email = emailUnico();
		var id = transacao.execute(s -> contaApi.provisionarOuVincular(email, "Maria")).id();
		var definir = contaApi.tokenDeAtivacao(id, email).orElseThrow(); // link DEFINIR da conta nova
		var redefinir = pedirToken(email);
		assertThat(redefinir(redefinir, NOVA).statusCode()).isEqualTo(200);
		assertLinkInvalido(redefinir(definir, "mais-uma-senha-forte-7"));
	}

	@Test
	void trocarSenhaRevogaLinksPendentes() throws Exception {
		var email = conta();
		var token = pedirToken(email);
		var nav = new Navegador(porta);
		assertThat(nav.login(email, ANTIGA).statusCode()).isEqualTo(200);
		assertThat(nav.post("/api/conta/senha", "{\"senhaAtual\":\"" + ANTIGA + "\",\"novaSenha\":\"" + NOVA + "\"}")
				.statusCode()).isEqualTo(200);
		assertLinkInvalido(redefinir(token, "mais-uma-senha-forte-7"));
	}

	@Test
	void contaInativaNaoRedefine() throws Exception {
		var email = conta();
		var token = pedirToken(email);
		var u = usuarios.findByEmail(email).orElseThrow();
		u.desativar();
		usuarios.save(u);
		assertLinkInvalido(redefinir(token, NOVA));
	}

	@Test
	void camposComTeto() throws Exception {
		assertThat(redefinir("t".repeat(101), NOVA).statusCode()).isEqualTo(400);
		assertThat(redefinir("t", "s".repeat(1025)).statusCode()).isEqualTo(400);
	}
}
