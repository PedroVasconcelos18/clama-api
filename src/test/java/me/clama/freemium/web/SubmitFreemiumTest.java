package me.clama.freemium.web;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import me.clama.BancoDeTeste;
import me.clama.CaixaDeSaidaEmMemoria;
import me.clama.EmailDeTeste;
import me.clama.conta.interno.Papel;
import me.clama.conta.interno.UsuarioRepository;
import me.clama.conta.web.Navegador;
import me.clama.freemium.interno.Bloqueios;
import me.clama.freemium.interno.PedidoFreemium;
import me.clama.infra.cripto.HmacDeIdentificador;
import me.clama.infra.fila.Fila;
import me.clama.infra.seguranca.LimiteDeTaxa;
import me.clama.pedido.NovoPedido;
import me.clama.pedido.PedidoApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/** Submit do freemium: cada portao na ordem de D6, com codigo e texto pastoral, e a criacao com o job na mesma transacao. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import({ BancoDeTeste.class, EmailDeTeste.class })
class SubmitFreemiumTest {

	static final WireMockServer TURNSTILE = new WireMockServer(WireMockConfiguration.options().dynamicPort());
	static {
		TURNSTILE.start();
	}

	@DynamicPropertySource
	static void turnstile(DynamicPropertyRegistry r) {
		r.add("clama.turnstile.url", () -> TURNSTILE.baseUrl() + "/siteverify");
		r.add("clama.turnstile.secret", () -> "segredo-de-teste");
	}

	static final String CPF = "529.982.247-25";
	static final String OUTRO_CPF = "111.444.777-35";
	static final String IP_DE_TESTE = "203.0.113.9";

	@LocalServerPort int porta;
	@Autowired JdbcTemplate jdbc;
	@Autowired UsuarioRepository usuarios;
	@Autowired PasswordEncoder encoder;
	@Autowired LimiteDeTaxa limite;
	@Autowired HmacDeIdentificador hmac;
	@Autowired PedidoApi pedidosApi;
	@Autowired PedidoFreemium servico;
	@Autowired Bloqueios bloqueios;
	@Autowired CaixaDeSaidaEmMemoria caixa;
	@MockitoSpyBean Fila fila;

	@BeforeEach
	@AfterEach
	void limpar() {
		limite.limpar();
		caixa.limpar();
		TURNSTILE.resetAll();
		planoGratuito(true);
		jdbc.update(BancoDeTeste.LIMPAR_FILA);
		jdbc.update("delete from pedido");
		jdbc.update("delete from freemium_bloqueio");
		jdbc.update("delete from usuario where email like 'frm-%' or email = 'maria.souza@gmail.com'");
		turnstileAprova(true);
	}

	// --- montagem ---

	/** O CHECK do plano exige o gratuito ativo: "faltar" e apagar a linha (nenhum pedido a referencia aqui). */
	void planoGratuito(boolean existe) {
		if (!existe) jdbc.update("delete from plano where tipo = 'GRATUITO'");
		else jdbc.update("""
				insert into plano (nome, descricao, valor_centavos, complexidade, tipo, ordem)
				values ('Gratuito', 'Sua primeira oração — por nossa conta', 0, 'SIMPLES', 'GRATUITO', 1000)
				on conflict (tipo) where tipo <> 'COMUM' do nothing""");
	}

	void turnstileAprova(boolean aprova) {
		TURNSTILE.stubFor(post("/siteverify").willReturn(okJson("{\"success\":" + aprova + "}")));
	}

	static String email() {
		return "frm-" + UUID.randomUUID() + "@exemplo.com";
	}

	Navegador anonimo() throws Exception {
		var nav = new Navegador(porta);
		nav.get("/api/auth/eu");
		return nav;
	}

	static Map<String, Object> corpo(String email, String cpf) {
		var m = new LinkedHashMap<String, Object>();
		m.put("nome", "Maria Souza");
		m.put("email", email);
		m.put("cpfCnpj", cpf);
		m.put("telefone", "11999990000");
		m.put("idade", 40);
		m.put("sexo", "feminino");
		m.put("pedidoOracao", "Pela minha família");
		m.put("consentAceito", true);
		m.put("turnstileToken", "token-ok");
		return m;
	}

	static Map<String, Object> com(Map<String, Object> base, Object... pares) {
		var m = new LinkedHashMap<>(base);
		for (int i = 0; i < pares.length; i += 2) {
			if (pares[i + 1] == null) m.remove((String) pares[i]);
			else m.put((String) pares[i], pares[i + 1]);
		}
		return m;
	}

	HttpResponse<String> enviar(Navegador nav, Map<String, Object> corpo) throws Exception {
		return nav.post("/api/freemium/pedidos", Navegador.JSON.writeValueAsString(corpo));
	}

	static JsonNode json(HttpResponse<String> res) {
		return Navegador.json(res);
	}

	void exige(HttpResponse<String> res, int status, String codigo, String pastoral) {
		assertThat(res.statusCode()).as(res.body()).isEqualTo(status);
		assertThat(json(res).get("codigo").asString()).isEqualTo(codigo);
		if (pastoral != null) assertThat(json(res).get("mensagemPastoral").asString()).isEqualTo(pastoral);
	}

	NovoPedido novo(String email, String cpf) {
		return new NovoPedido("Maria Souza", email, cpf, null, null, null, "Pela família", null, null, null, true, null);
	}

	int contar(String tabela) {
		return jdbc.queryForObject("select count(*) from " + tabela, Integer.class);
	}

	int jobsDeConfirmacao() {
		return jdbc.queryForObject("select count(*) from scheduled_tasks where task_name = 'confirmacao-freemium'", Integer.class);
	}

	/** Bloqueio de teste com e-mail e CPF aleatorios (so o IP importa). */
	void bloquearIp(String ip, java.time.Instant criadoEm) {
		jdbc.update("insert into freemium_bloqueio (email_hmac, cpf_hmac, ip_hmac, criado_em) values (?, ?, ?, ?)",
				hmac.email(email()), hmac.cpf(UUID.randomUUID().toString()), hmac.ip(ip), OffsetDateTime.ofInstant(criadoEm, java.time.ZoneOffset.UTC));
	}

	// --- criacao ---

	@Test
	void criaPedidoAguardandoEEnfileiraOJobSemContaNemEmailDeSenha() throws Exception {
		var email = email();
		var res = enviar(anonimo(), corpo(email, CPF));

		assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
		var id = UUID.fromString(json(res).get("id").asString());
		assertThat(json(res).get("status").asString()).isEqualTo("AGUARDANDO_CONFIRMACAO_EMAIL");
		assertThat(pedidosApi.resumo(id).orElseThrow().status().name()).isEqualTo("AGUARDANDO_CONFIRMACAO_EMAIL");
		assertThat(jdbc.queryForObject("select gratuito from pedido where id = ?", Boolean.class, id)).isTrue();
		assertThat(jdbc.queryForObject("select count(*) from scheduled_tasks where task_name = 'confirmacao-freemium' and task_instance = ?",
				Integer.class, "confirmacao-freemium/" + id)).isOne();
		TURNSTILE.verify(1, postRequestedFor(urlEqualTo("/siteverify")).withRequestBody(containing("response=token-ok")));
		// Review Focus 1: o submit nao cria conta e nao manda "definir senha"
		assertThat(contar("usuario where email = '" + email + "'")).isZero();
		assertThat(caixa.enviados()).isEmpty();
		assertThat(contar("freemium_bloqueio")).isZero();
		assertThat(contar("freemium_confirmacao")).as("o link so nasce no job").isZero();
	}

	@Test
	void telefoneVazioOuAusenteEAceito() throws Exception {
		var nav = anonimo();
		assertThat(enviar(nav, com(corpo(email(), CPF), "telefone", "")).statusCode()).isEqualTo(201);
		assertThat(enviar(nav, com(corpo(email(), OUTRO_CPF), "telefone", null)).statusCode()).isEqualTo(201);
	}

	@Test
	void deviceHashDoFrontAntigoEIgnorado() throws Exception {
		assertThat(enviar(anonimo(), com(corpo(email(), CPF), "deviceHash", "d".repeat(500))).statusCode()).isEqualTo(201);
	}

	@Test
	void mantemCsrfMasNaoExigeLogin() throws Exception {
		var sem = enviar(new Navegador(porta), corpo(email(), CPF));
		exige(sem, 403, "csrf_invalido", null);
		assertThat(contar("pedido")).isZero();
	}

	// --- portoes, um a um e na ordem ---

	@Test
	void camposInvalidosGanhamDoLimiteEDoCaptcha() throws Exception {
		turnstileAprova(false);
		var nav = anonimo();
		for (int i = 0; i < 5; i++) {
			var res = enviar(nav, com(corpo(email(), CPF), "nome", "A"));
			assertThat(res.statusCode()).isEqualTo(400);
			assertThat(json(res).get("codigo").asString()).isEqualTo("dados_invalidos");
		}
		TURNSTILE.verify(0, postRequestedFor(urlEqualTo("/siteverify")));
		// 5 invalidos nao gastaram o limite de 5/h: o 6o, valido, passa
		turnstileAprova(true);
		assertThat(enviar(nav, corpo(email(), CPF)).statusCode()).isEqualTo(201);
	}

	@Test
	void captchaGanhaDoDescartavelEDescartavelGanhaDeConta() throws Exception {
		var nav = anonimo();
		var usuarioExistente = "frm-" + UUID.randomUUID() + "@mailinator.com";
		Navegador.usuario(usuarios, encoder, usuarioExistente, "senha-forte-123", Papel.CLIENTE);
		turnstileAprova(false);
		exige(enviar(nav, corpo(usuarioExistente, CPF)), 400, "captcha_invalido",
				"Verificação anti-robô falhou. Atualize a página e tente de novo.");
		turnstileAprova(true);
		exige(enviar(nav, corpo(usuarioExistente, CPF)), 400, "email_descartavel",
				"Use um e-mail principal para receber sua oração — e-mails temporários não funcionam aqui.");
		assertThat(contar("pedido")).isZero();
	}

	@Test
	void turnstileIndisponivelContaComoCaptchaInvalido() throws Exception {
		TURNSTILE.stubFor(post("/siteverify").willReturn(serviceUnavailable()));
		exige(enviar(anonimo(), corpo(email(), CPF)), 400, "captcha_invalido", null);
	}

	@Test
	void tokenDoTurnstileAusenteEErroDeCampo() throws Exception {
		var res = enviar(anonimo(), com(corpo(email(), CPF), "turnstileToken", null));
		assertThat(res.statusCode()).isEqualTo(400);
		assertThat(json(res).get("erros").get(0).get("campo").asString()).isEqualTo("turnstileToken");
	}

	@Test
	void seisPorHoraPorIpDa429NoSexto() throws Exception {
		turnstileAprova(false); // 400 barato; o limite conta antes do Turnstile
		var nav = anonimo();
		for (int i = 0; i < 5; i++) assertThat(enviar(nav, corpo(email(), CPF)).statusCode()).isEqualTo(400);
		exige(enviar(nav, corpo(email(), CPF)), 429, "limite_excedido", null);
	}

	/**
	 * Todas as barreiras de banco armadas contra o mesmo pedido; cada rodada tira a que acabou de responder e a seguinte
	 * responde (D6, 5 a 10), com o texto pastoral de cada uma.
	 */
	@Test
	void portoesDeBancoNaOrdemComCodigoETexto() throws Exception {
		var nav = anonimo();
		var email = email();
		var dono = Navegador.usuario(usuarios, encoder, email, "senha-forte-123", Papel.CLIENTE);
		var pendente = pedidosApi.criarFreemium(novo(email, CPF), IP_DE_TESTE);
		bloquearIp("0:0:0:0:0:0:0:1", java.time.Instant.now()); // o IP do cliente de teste varia: arma os dois
		bloquearIp("127.0.0.1", java.time.Instant.now());
		bloquearIp("127.0.0.1", java.time.Instant.now());
		bloquearIp("0:0:0:0:0:0:0:1", java.time.Instant.now());
		bloquearIp("127.0.0.1", java.time.Instant.now());
		bloquearIp("0:0:0:0:0:0:0:1", java.time.Instant.now());
		jdbc.update("insert into freemium_bloqueio (email_hmac, cpf_hmac, criado_em) values (?, ?, now())", hmac.email(email), hmac.cpf(CPF));
		var antes = contar("pedido");

		// 5. conta existente
		exige(enviar(nav, corpo(email, CPF)), 409, "conta_existente", "Você já tem conta com a gente. Faça login pra fazer um novo pedido.");
		jdbc.update("delete from usuario where id = ?", dono.getId());
		// 6. pedido pendente
		exige(enviar(nav, corpo(email, CPF)), 409, "pedido_em_andamento",
				"Você já tem um pedido aguardando confirmação. Confira seu e-mail para finalizar — se não achar, olha na aba de spam.");
		jdbc.update("delete from pedido where id = ?", pendente);
		// 7. bloqueio por e-mail ou CPF
		var jaUsado = "Você já recebeu sua oração gratuita. Sempre que quiser, pode fazer um novo pedido pela página principal.";
		exige(enviar(nav, corpo(email, CPF)), 409, "freemium_ja_usado", jaUsado);
		jdbc.update("delete from freemium_bloqueio where cpf_hmac = ?", hmac.cpf(CPF));
		// 8. limiar de IP (3 do mesmo IP na ultima hora)
		exige(enviar(nav, corpo(email, CPF)), 409, "freemium_ja_usado", jaUsado);
		jdbc.update("delete from freemium_bloqueio");
		// 9. plano gratuito
		planoGratuito(false);
		exige(enviar(nav, corpo(email, CPF)), 503, "freemium_indisponivel", null);
		planoGratuito(true);
		assertThat(contar("pedido")).isEqualTo(antes - 1);
		assertThat(jobsDeConfirmacao()).isZero();
		// 10. cria (a 6a tentativa da hora: zera o limite de 5/h, que tem teste proprio)
		limite.limpar();
		assertThat(enviar(nav, corpo(email, CPF)).statusCode()).isEqualTo(201);
	}

	@Test
	void contaPeloEmailCanonicoDoGmail() throws Exception {
		Navegador.usuario(usuarios, encoder, "maria.souza@gmail.com", "senha-forte-123", Papel.CLIENTE);
		exige(enviar(anonimo(), corpo("mariasouza+oracao@gmail.com", CPF)), 409, "conta_existente", null);
		assertThat(contar("pedido")).isZero();
	}

	@Test
	void contaPeloCpfComDonoMesmoComMascaraEOutroEmail() throws Exception {
		var dono = Navegador.usuario(usuarios, encoder, email(), "senha-forte-123", Papel.CLIENTE);
		var existente = pedidosApi.criarFreemium(novo(email(), "52998224725"), IP_DE_TESTE);
		jdbc.update("update pedido set usuario_id = ? where id = ?", dono.getId(), existente);
		jdbc.update("update pedido set status = 'CONFIRMACAO_EXPIRADA' where id = ?", existente); // a conta, nao o pedido, que barra
		exige(enviar(anonimo(), corpo(email(), CPF)), 409, "conta_existente", null); // CPF com mascara
	}

	@Test
	void pendenteBarraDentroDaJanelaEPassaFora() throws Exception {
		var nav = anonimo();
		var email = email();
		assertThat(enviar(nav, corpo(email, CPF)).statusCode()).isEqualTo(201);
		// mesmo e-mail com alias e outro CPF; mesmo CPF e outro e-mail
		exige(enviar(nav, corpo(email, OUTRO_CPF)), 409, "pedido_em_andamento", null);
		exige(enviar(nav, corpo(email(), "529.982.247-25")), 409, "pedido_em_andamento", null);
		jdbc.update("update pedido set criado_em = now() - interval '25 hours'");
		assertThat(enviar(nav, corpo(email, CPF)).statusCode()).as("fora da janela de 24 h").isEqualTo(201);
	}

	@Test
	void bloqueioPorEmailComAliasEPorCpfComMascara() throws Exception {
		var nav = anonimo();
		jdbc.update("insert into freemium_bloqueio (email_hmac, cpf_hmac, criado_em) values (?, ?, now())",
				hmac.email("ana.paula@gmail.com"), hmac.cpf("168.995.350-09"));
		exige(enviar(nav, corpo("anapaula+x@gmail.com", OUTRO_CPF)), 409, "freemium_ja_usado", null); // e-mail
		exige(enviar(nav, corpo(email(), "16899535009")), 409, "freemium_ja_usado", null); // CPF sem mascara
		assertThat(enviar(nav, corpo(email(), OUTRO_CPF)).statusCode()).isEqualTo(201);
	}

	// --- limiar de IP (Review Focus 5), direto no servico para controlar o IP ---

	@Test
	void limiarDeIpDoisPassamTresBloqueiam() {
		var agora = java.time.Instant.now();
		bloquearIp(IP_DE_TESTE, agora);
		bloquearIp(IP_DE_TESTE, agora);
		assertThat(servico.submeter(novo(email(), CPF), IP_DE_TESTE)).isNotNull(); // 2 < 3
		jdbc.update("delete from pedido");
		bloquearIp(IP_DE_TESTE, agora);
		var erro = org.junit.jupiter.api.Assertions.assertThrows(me.clama.infra.web.ErroDeNegocio.class,
				() -> servico.submeter(novo(email(), OUTRO_CPF), IP_DE_TESTE)); // 3 >= 3: inclusivo
		assertThat(erro.codigo()).isEqualTo("freemium_ja_usado");
		assertThat(servico.submeter(novo(email(), OUTRO_CPF), "198.51.100.7")).as("outro IP").isNotNull();
	}

	@Test
	void bloqueiosForaDaJanelaDeUmaHoraNaoContam() {
		var agora = java.time.Instant.now();
		for (int i = 0; i < 3; i++) bloquearIp(IP_DE_TESTE, agora.minus(Duration.ofMinutes(61)));
		assertThat(servico.submeter(novo(email(), CPF), IP_DE_TESTE)).isNotNull();
	}

	@Test
	void ipNuloOuVazioNaoEChecado() {
		var agora = java.time.Instant.now();
		for (int i = 0; i < 5; i++) bloquearIp(IP_DE_TESTE, agora);
		assertThat(servico.submeter(novo(email(), CPF), null)).isNotNull();
		assertThat(servico.submeter(novo(email(), OUTRO_CPF), " ")).isNotNull();
	}

	@Test
	void limiarEJanelaVemDaConfiguracaoEBloqueiosContamPorIp() {
		bloquearIp(IP_DE_TESTE, java.time.Instant.now().minus(Duration.ofMinutes(59)));
		assertThat(bloqueios.contarPorIp(hmac.ip(IP_DE_TESTE), java.time.Instant.now().minus(Duration.ofHours(1)))).isOne();
		assertThat(bloqueios.contarPorIp(hmac.ip(IP_DE_TESTE), java.time.Instant.now().minus(Duration.ofMinutes(30)))).isZero();
		assertThat(bloqueios.contarPorIp(hmac.ip("198.51.100.1"), java.time.Instant.now().minus(Duration.ofHours(1)))).isZero();
	}

	@Test
	void bloqueioCriadoExatamenteNoInicioDaJanelaConta() {
		var desde = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS); // o banco guarda micros
		bloquearIp(IP_DE_TESTE, desde);
		assertThat(bloqueios.contarPorIp(hmac.ip(IP_DE_TESTE), desde)).as("criado_em == desde conta").isOne();
		assertThat(bloqueios.contarPorIp(hmac.ip(IP_DE_TESTE), desde.plus(Duration.ofNanos(1000)))).as("1 us depois nao").isZero();
	}

	@Test
	void inserirBloqueioNaoAbortaEmConflito() {
		var agora = java.time.Instant.now();
		assertThat(bloqueios.inserir("e1", "c1", null, agora)).isTrue();
		assertThat(bloqueios.inserir("e1", "c2", null, agora)).as("e-mail repetido").isFalse();
		assertThat(bloqueios.inserir("e2", "c1", null, agora)).as("CPF repetido").isFalse();
		assertThat(bloqueios.existe("e1", "x")).isTrue();
		assertThat(bloqueios.existe("x", "c1")).isTrue();
		assertThat(bloqueios.existe("x", null)).isFalse();
	}

	// --- transacao: pedido e job juntos ---

	@Test
	void falhaAoEnfileirarDa500ESemPedido() throws Exception {
		doThrow(new IllegalStateException("fila fora do ar")).when(fila).enfileirar(any(), any(), any(), any());
		var res = enviar(anonimo(), corpo(email(), CPF));
		assertThat(res.statusCode()).isEqualTo(500);
		assertThat(contar("pedido")).as("sem pedido orfao").isZero();
		assertThat(jobsDeConfirmacao()).isZero();
	}

	@Test
	void semPlanoGratuitoDa503SemPedidoNemJob() throws Exception {
		planoGratuito(false);
		exige(enviar(anonimo(), corpo(email(), CPF)), 503, "freemium_indisponivel", "Estamos com problemas momentâneos. Tente em alguns minutos.");
		assertThat(contar("pedido")).isZero();
		assertThat(jobsDeConfirmacao()).isZero();
	}
}
