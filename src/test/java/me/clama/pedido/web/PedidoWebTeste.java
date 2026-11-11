package me.clama.pedido.web;

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import me.clama.BancoDeTeste;
import me.clama.conta.interno.Papel;
import me.clama.conta.interno.UsuarioRepository;
import me.clama.conta.web.Navegador;
import me.clama.infra.cripto.HmacDeIdentificador;
import me.clama.infra.seguranca.LimiteDeTaxa;
import me.clama.pedido.GeracaoSolicitada;
import me.clama.pedido.interno.Pedido;
import me.clama.pedido.interno.PedidoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

/** Base dos testes HTTP de pedido: um WireMock de Turnstile por JVM, banco limpo antes e depois, fixtures de plano e pedido. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import({ BancoDeTeste.class, PedidoWebTeste.Capturador.class })
abstract class PedidoWebTeste {

	static final WireMockServer TURNSTILE = new WireMockServer(WireMockConfiguration.options().dynamicPort());
	static {
		TURNSTILE.start();
	}

	@DynamicPropertySource
	static void turnstile(DynamicPropertyRegistry r) {
		r.add("clama.turnstile.url", () -> TURNSTILE.baseUrl() + "/siteverify");
		r.add("clama.turnstile.secret", () -> "segredo-de-teste");
	}

	/** Guarda os GeracaoSolicitada publicados (os eventos das threads do servidor nao chegam ao RecordApplicationEvents). */
	@TestComponent
	public static class Capturador {
		final List<UUID> geracoes = new CopyOnWriteArrayList<>();

		@EventListener
		void ao(GeracaoSolicitada e) {
			geracoes.add(e.pedidoId());
		}
	}

	@LocalServerPort int porta;
	@Autowired JdbcTemplate jdbc;
	@Autowired UsuarioRepository usuarios;
	@Autowired PasswordEncoder encoder;
	@Autowired LimiteDeTaxa limite;
	@Autowired PedidoRepository pedidos;
	@Autowired HmacDeIdentificador hmac;
	@Autowired Clock relogio;
	@Autowired Capturador capturador;

	@BeforeEach
	@AfterEach
	void limpar() { // banco compartilhado: nao deixa linha para os outros testes
		limite.limpar();
		capturador.geracoes.clear();
		TURNSTILE.resetAll();
		jdbc.update("delete from pedido");
		jdbc.update("delete from usuario where email like 'ped-%'");
		jdbc.update("delete from plano where tipo = 'COMUM'");
		jdbc.update("delete from instituicao");
		jdbc.update("""
				insert into plano (nome, descricao, valor_centavos, complexidade, tipo, ordem)
				values ('Gratuito', 'Sua primeira oração — por nossa conta', 0, 'SIMPLES', 'GRATUITO', 1000)
				on conflict (tipo) where tipo <> 'COMUM' do nothing""");
	}

	void turnstileAprova(boolean aprova) {
		TURNSTILE.stubFor(post("/siteverify").willReturn(okJson("{\"success\":" + aprova + "}")));
	}

	Long plano(String nome, long valorCentavos) {
		return jdbc.queryForObject("""
				insert into plano (nome, valor_centavos, complexidade, tipo, ordem) values (?, ?, 'SIMPLES', 'COMUM', 1)
				returning id""", Long.class, nome, valorCentavos);
	}

	Long instituicao(String nome, boolean ativa) {
		return jdbc.queryForObject("insert into instituicao (nome, ativo) values (?, ?) returning id", Long.class, nome, ativa);
	}

	Long planoTecnico(String tipo) {
		return jdbc.queryForObject("select id from plano where tipo = ?", Long.class, tipo);
	}

	record Logado(Navegador nav, Long id, String email) {}

	Logado logado() throws Exception {
		var email = "ped-" + UUID.randomUUID() + "@exemplo.com";
		var u = Navegador.usuario(usuarios, encoder, email, "senha-forte-123", Papel.CLIENTE);
		var nav = new Navegador(porta);
		var res = nav.login(email, "senha-forte-123");
		if (res.statusCode() != 200) throw new IllegalStateException("login falhou: " + res.body());
		return new Logado(nav, u.getId(), email);
	}

	/** Navegador anonimo que ja pegou o cookie XSRF (como o front faz antes de um POST). */
	Navegador anonimo() throws Exception {
		var nav = new Navegador(porta);
		nav.get("/api/auth/eu");
		return nav;
	}

	/** Corpo valido de pedido pago (sem plano; valor de R$ 30,00). */
	Map<String, Object> corpo() {
		var m = new java.util.LinkedHashMap<String, Object>();
		m.put("nome", "Maria Souza");
		m.put("email", "maria@exemplo.com");
		m.put("cpfCnpj", "529.982.247-25");
		m.put("telefone", "11999990000");
		m.put("idade", 40);
		m.put("sexo", "feminino");
		m.put("pedidoOracao", "Pela minha família");
		m.put("valorCentavos", 3000);
		m.put("consentAceito", true);
		return m;
	}

	Map<String, Object> com(Map<String, Object> base, Object... paresChaveValor) {
		var m = new java.util.LinkedHashMap<>(base);
		for (int i = 0; i < paresChaveValor.length; i += 2) {
			if (paresChaveValor[i + 1] == null) m.remove((String) paresChaveValor[i]);
			else m.put((String) paresChaveValor[i], paresChaveValor[i + 1]);
		}
		return m;
	}

	static String json(Object o) {
		return Navegador.JSON.writeValueAsString(o);
	}

	static tools.jackson.databind.JsonNode corpo(HttpResponse<String> res) {
		return Navegador.json(res);
	}

	/** Mensagem do erro no campo, ou null. */
	static String erroDe(HttpResponse<String> res, String campo) {
		for (var e : corpo(res).path("erros")) if (e.get("campo").asString().equals(campo)) return e.get("mensagem").asString();
		return null;
	}

	/** Pedido no banco, pelo caminho do dominio (a PII e cifrada). */
	Pedido pedido(Long usuarioId, Long planoId, Instant criadoEm) {
		var d = new Pedido.Dados(usuarioId, planoId, null, 3000, "Maria Souza", "maria@exemplo.com", "52998224725", null, null, null,
				"Pela família", criadoEm, null, "1");
		return pedidos.saveAndFlush(Pedido.novo(d, hmac, criadoEm));
	}

	Pedido salvar(Pedido p) {
		return pedidos.saveAndFlush(p);
	}

	UUID idDoUnicoPedido() {
		return jdbc.queryForObject("select id from pedido", UUID.class);
	}
}
