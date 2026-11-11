package me.clama.freemium.interno;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import me.clama.BancoDeTeste;
import me.clama.CaixaDeSaidaEmMemoria;
import me.clama.EmailDeTeste;
import me.clama.conta.interno.Papel;
import me.clama.conta.interno.UsuarioRepository;
import me.clama.conta.web.Navegador;
import me.clama.infra.cripto.HmacDeIdentificador;
import me.clama.infra.email.EnvioAssincrono;
import me.clama.infra.seguranca.LimiteDeTaxa;
import me.clama.infra.web.ErroDeNegocio;
import me.clama.pedido.PedidoApi;
import me.clama.pedido.StatusPedido;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * A saga da confirmacao (D3, D4, D5) por HTTP e pelo servico: tudo numa transacao, o "definir senha" so no commit, e
 * a saga que desfaz nao deixa conta, bloqueio, e-mail nem link gasto (Review Focus 1, 2 e 4).
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import({ BancoDeTeste.class, EmailDeTeste.class })
class ConfirmacaoFreemiumTest {

	static final String CPF = "529.982.247-25";
	static final String SENHA = "Bem-vinda ao Clama — defina sua senha";
	static final String INVALIDO = "Esse link não é mais válido. Faça um novo pedido.";
	static final String EXPIRADO = "Esse link expirou. Faça um novo pedido pra receber sua oração grátis.";

	@LocalServerPort int porta;
	@Autowired JdbcTemplate jdbc;
	@Autowired PedidoApi pedidos;
	@Autowired ConfirmacaoFreemium saga;
	@Autowired HmacDeIdentificador hmac;
	@Autowired CaixaDeSaidaEmMemoria caixa;
	@Autowired EnvioAssincrono envio;
	@Autowired LimiteDeTaxa limite;
	@Autowired UsuarioRepository usuarios;
	@Autowired PasswordEncoder encoder;

	@BeforeEach
	@AfterEach
	void limpar() throws Exception {
		esperarEnvios();
		caixa.limpar();
		limite.limpar();
		jdbc.update(BancoDeTeste.LIMPAR_FILA);
		jdbc.update("delete from pedido");
		jdbc.update("delete from freemium_bloqueio");
		jdbc.update("delete from usuario where email like 'frm-%'");
		FreemiumDeTeste.planoGratuito(jdbc);
	}

	void esperarEnvios() throws Exception {
		for (int i = 0; i < 500 && envio.pendentes() > 0; i++) Thread.sleep(10);
	}

	String email = "frm-" + UUID.randomUUID() + "@exemplo.com";

	UUID pedido(String email) {
		return pedidos.criarFreemium(FreemiumDeTeste.novo(email, CPF), "203.0.113.9");
	}

	/** Pedido pendente com um link (so o hash vai ao banco) valido por 24 h. */
	record Par(UUID pedido, String token) {}

	Par pendente(String email) {
		var id = pedido(email);
		return new Par(id, FreemiumDeTeste.link(jdbc, id, Instant.now().plus(Duration.ofHours(24))));
	}

	HttpResponse<String> confirmar(Navegador nav, String token) throws Exception {
		return nav.post("/api/freemium/confirmacao", token == null ? "{}" : "{\"token\":\"" + token + "\"}");
	}

	Navegador anonimo() throws Exception {
		var nav = new Navegador(porta);
		nav.get("/api/auth/eu");
		return nav;
	}

	int contar(String consulta) {
		return jdbc.queryForObject("select count(*) from " + consulta, Integer.class);
	}

	StatusPedido status(UUID id) {
		return pedidos.resumo(id).orElseThrow().status();
	}

	boolean linkUsado(String token) {
		return jdbc.queryForObject("select usado_em is not null from freemium_confirmacao where hash = ?", Boolean.class, Confirmacoes.hash(token));
	}

	void exige(HttpResponse<String> res, int status, String codigo, String pastoral) {
		assertThat(res.statusCode()).as(res.body()).isEqualTo(status);
		JsonNode j = Navegador.json(res);
		assertThat(j.get("codigo").asString()).isEqualTo(codigo);
		if (pastoral != null) assertThat(j.get("mensagemPastoral").asString()).isEqualTo(pastoral);
	}

	/** Nada saiu nem vai sair: espera os envios assincronos e confere a caixa. */
	void semEmailAlgum() throws Exception {
		esperarEnvios();
		assertThat(caixa.enviados()).isEmpty();
	}

	// --- sucesso ---

	@Test
	void confirmaViraPagoCriaContaBloqueioEEnfileiraAGeracaoSoDepoisDoCommitVemOEmailDeSenha() throws Exception {
		var p = pendente(email);
		var res = confirmar(anonimo(), p.token());

		assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
		assertThat(res.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
		assertThat(Navegador.json(res).get("id").asString()).isEqualTo(p.pedido().toString());
		assertThat(Navegador.json(res).get("status").asString()).isEqualTo("PAGO");
		assertThat(status(p.pedido())).isEqualTo(StatusPedido.PAGO);
		assertThat(contar("usuario where email = '" + email + "'")).isOne();
		assertThat(jdbc.queryForObject("select usuario_id is not null from pedido where id = ?", Boolean.class, p.pedido())).isTrue();
		assertThat(contar("freemium_bloqueio")).isOne();
		assertThat(jdbc.queryForObject("select ip_hmac from freemium_bloqueio", String.class)).hasSize(64);
		assertThat(jdbc.queryForObject("select email_hmac from freemium_bloqueio", String.class)).isEqualTo(hmac.email(email));
		assertThat(contar("scheduled_tasks where task_name = 'gerar-oracao' and task_instance = '" + "gerar-oracao/" + p.pedido() + "'")).isOne();
		assertThat(linkUsado(p.token())).isTrue();
		var senha = caixa.aguardarPara(email, 1);
		assertThat(senha).singleElement().satisfies(e -> assertThat(e.email().assunto()).isEqualTo(SENHA));
		assertThat(caixa.para("contato@clama.me")).isEmpty();
	}

	// --- links ---

	@Test
	void linkInexistenteELinkInvalido() throws Exception {
		exige(confirmar(anonimo(), "nao-existe-" + UUID.randomUUID()), 400, "link_invalido", INVALIDO);
		semEmailAlgum();
	}

	@Test
	void linkUsadoTemOMesmoTextoDoInvalidoENaoRepete() throws Exception {
		var p = pendente(email);
		var nav = anonimo();
		assertThat(confirmar(nav, p.token()).statusCode()).isEqualTo(200);
		exige(confirmar(nav, p.token()), 400, "link_invalido", INVALIDO);
		assertThat(contar("freemium_bloqueio")).isOne();
		assertThat(contar("usuario where email = '" + email + "'")).isOne();
	}

	@Test
	void linkVencidoTemTextoProprioENadaMuda() throws Exception {
		var id = pedido(email);
		var token = FreemiumDeTeste.link(jdbc, id, Instant.now().minusSeconds(1));
		exige(confirmar(anonimo(), token), 400, "link_expirado", EXPIRADO);
		assertThat(status(id)).isEqualTo(StatusPedido.AGUARDANDO_CONFIRMACAO_EMAIL);
		assertThat(linkUsado(token)).isFalse();
		assertThat(contar("usuario where email = '" + email + "'")).isZero();
		assertThat(contar("freemium_bloqueio")).isZero();
		semEmailAlgum();
	}

	/** Review Focus 4: pedido expirado pela reconciliacao nao gera nada, mesmo com o link de pe. */
	@Test
	void pedidoForaDoEstadoEInvalidoENadaMuda() throws Exception {
		var p = pendente(email);
		jdbc.update("update pedido set status = 'CONFIRMACAO_EXPIRADA' where id = ?", p.pedido());
		exige(confirmar(anonimo(), p.token()), 400, "link_invalido", INVALIDO);
		assertThat(status(p.pedido())).isEqualTo(StatusPedido.CONFIRMACAO_EXPIRADA);
		assertThat(linkUsado(p.token())).isFalse();
		assertThat(contar("usuario where email = '" + email + "'")).isZero();
		assertThat(contar("freemium_bloqueio")).isZero();
		assertThat(contar("scheduled_tasks where task_name = 'gerar-oracao'")).isZero();
		semEmailAlgum();
	}

	@Test
	void corpoSemTokenOuVazioE400() throws Exception {
		var nav = anonimo();
		assertThat(confirmar(nav, null).statusCode()).isEqualTo(400);
		assertThat(confirmar(nav, "").statusCode()).isEqualTo(400);
		assertThat(nav.post("/api/freemium/confirmacao", "{\"token\":\"" + "a".repeat(201) + "\"}").statusCode()).isEqualTo(400);
	}

	@Test
	void mantemCsrfMasNaoExigeLogin() throws Exception {
		var p = pendente(email);
		exige(confirmar(new Navegador(porta), p.token()), 403, "csrf_invalido", null);
		assertThat(status(p.pedido())).isEqualTo(StatusPedido.AGUARDANDO_CONFIRMACAO_EMAIL);
	}

	@Test
	void trintaPorHoraPorIpDa429NoTrintaEUm() throws Exception {
		var nav = anonimo();
		for (int i = 0; i < 30; i++) assertThat(confirmar(nav, "x" + i).statusCode()).isEqualTo(400);
		exige(confirmar(nav, "x31"), 429, "limite_excedido", null);
		// o 429 vem antes da saga: nem um link valido passa
		var p = pendente(email);
		assertThat(confirmar(nav, p.token()).statusCode()).isEqualTo(429);
		assertThat(status(p.pedido())).isEqualTo(StatusPedido.AGUARDANDO_CONFIRMACAO_EMAIL);
	}

	// --- saga desfeita (Review Focus 1) ---

	@Test
	void bloqueioQueColideDa409ENadaMudaELinkSegueValido() throws Exception {
		var p = pendente(email);
		jdbc.update("insert into freemium_bloqueio (email_hmac, cpf_hmac, criado_em) values (?, ?, now())", hmac.email(email), hmac.cpf("111.444.777-35"));
		var nav = anonimo();

		exige(confirmar(nav, p.token()), 409, "freemium_ja_usado",
				"Você já recebeu sua oração gratuita. Sempre que quiser, pode fazer um novo pedido pela página principal.");

		assertThat(contar("usuario where email = '" + email + "'")).as("sem conta").isZero();
		assertThat(status(p.pedido())).isEqualTo(StatusPedido.AGUARDANDO_CONFIRMACAO_EMAIL);
		assertThat(jdbc.queryForObject("select usuario_id is null from pedido where id = ?", Boolean.class, p.pedido())).isTrue();
		assertThat(linkUsado(p.token())).as("link segue valido").isFalse();
		assertThat(contar("scheduled_tasks where task_name = 'gerar-oracao'")).as("sem geracao").isZero();
		assertThat(contar("freemium_bloqueio")).as("so o de teste").isOne();
		semEmailAlgum(); // nenhum "definir senha" de uma conta que foi desfeita

		// o link continua usavel: sem o bloqueio, confirma
		jdbc.update("delete from freemium_bloqueio");
		assertThat(confirmar(nav, p.token()).statusCode()).isEqualTo(200);
		assertThat(caixa.aguardarPara(email, 1)).hasSize(1);
	}

	// --- concorrencia (Review Focus 2) ---

	/** Chama todas ao mesmo tempo (trava de partida) e devolve o resultado ou a excecao de cada uma. */
	static List<Object> emParalelo(List<Callable<?>> tarefas) throws Exception {
		var largada = new CountDownLatch(1);
		var pronto = new CountDownLatch(tarefas.size());
		var pool = Executors.newFixedThreadPool(tarefas.size());
		try {
			var futuros = new ArrayList<Future<Object>>();
			for (var t : tarefas) {
				futuros.add(pool.submit(() -> {
					pronto.countDown();
					largada.await();
					try { return t.call(); } catch (Exception e) { return e; }
				}));
			}
			pronto.await();
			largada.countDown();
			var out = new ArrayList<Object>();
			for (var f : futuros) out.add(f.get());
			return out;
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void duasConfirmacoesDoMesmoLinkDaoUmSucessoSo() throws Exception {
		var p = pendente(email);
		var r = emParalelo(List.of(() -> saga.confirmar(p.token()), () -> saga.confirmar(p.token())));

		assertThat(r.stream().filter(UUID.class::isInstance)).hasSize(1);
		assertThat(r.stream().filter(ErroDeNegocio.class::isInstance)).singleElement()
				.satisfies(e -> assertThat(((ErroDeNegocio) e).codigo()).isEqualTo("link_invalido"));
		assertThat(contar("usuario where email = '" + email + "'")).isOne();
		assertThat(contar("freemium_bloqueio")).isOne();
		assertThat(contar("scheduled_tasks where task_name = 'gerar-oracao'")).isOne();
		assertThat(caixa.aguardarPara(email, 1)).hasSize(1);
	}

	@Test
	void doisPedidosDaMesmaPessoaConfirmadosJuntosDaoUmaContaUmBloqueioEUm409() throws Exception {
		var a = pendente(email);
		var b = pendente(email);
		var r = emParalelo(List.of(() -> saga.confirmar(a.token()), () -> saga.confirmar(b.token())));

		assertThat(r.stream().filter(UUID.class::isInstance)).hasSize(1);
		assertThat(r.stream().filter(ErroDeNegocio.class::isInstance)).singleElement()
				.satisfies(e -> assertThat(((ErroDeNegocio) e).codigo()).isEqualTo("freemium_ja_usado"));
		assertThat(contar("usuario where email = '" + email + "'")).isOne();
		assertThat(contar("freemium_bloqueio")).isOne();
		assertThat(contar("scheduled_tasks where task_name = 'gerar-oracao'")).isOne();
		// quem perdeu nao mudou: segue pendente, sem dono, com o link valido
		var perdedor = status(a.pedido()) == StatusPedido.PAGO ? b : a;
		assertThat(status(perdedor.pedido())).isEqualTo(StatusPedido.AGUARDANDO_CONFIRMACAO_EMAIL);
		assertThat(linkUsado(perdedor.token())).isFalse();
		assertThat(contar("pedido where status = 'PAGO'")).isOne();
		assertThat(caixa.aguardarPara(email, 1)).hasSize(1);
		esperarEnvios();
		assertThat(caixa.para(email)).hasSize(1);
	}

	// --- conta existente ---

	@Test
	void contaExistenteEVinculadaSemEmailDeSenha() throws Exception {
		var dona = Navegador.usuario(usuarios, encoder, email, "senha-forte-123", Papel.CLIENTE);
		var p = pendente(email);

		assertThat(confirmar(anonimo(), p.token()).statusCode()).isEqualTo(200);

		assertThat(status(p.pedido())).isEqualTo(StatusPedido.PAGO);
		assertThat(jdbc.queryForObject("select usuario_id from pedido where id = ?", Long.class, p.pedido())).isEqualTo(dona.getId());
		assertThat(contar("usuario where email = '" + email + "'")).isOne();
		assertThat(contar("freemium_bloqueio")).isOne();
		semEmailAlgum();
	}
}
