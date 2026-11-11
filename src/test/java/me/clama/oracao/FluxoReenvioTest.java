package me.clama.oracao;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import me.clama.BancoDeTeste;
import me.clama.CaixaDeSaidaEmMemoria;
import me.clama.EmailDeTeste;
import me.clama.conta.interno.Papel;
import me.clama.conta.interno.UsuarioRepository;
import me.clama.conta.web.Navegador;
import me.clama.infra.cripto.HmacDeIdentificador;
import me.clama.infra.email.EnvioAssincrono;
import me.clama.infra.fila.FilaLigada;
import me.clama.pedido.interno.Pedido;
import me.clama.pedido.interno.PedidoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Ponta a ponta do admin: gratuidade (o pipeline gera e envia, 1o e-mail), reenvio (2o e-mail, chave nova) e um segundo
 * reenvio (3o, outra chave). O pedido continua ENVIADA e a Claude so e chamada uma vez.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import({ BancoDeTeste.class, EmailDeTeste.class })
@ActiveProfiles("test")
@FilaLigada
class FluxoReenvioTest {

	static final WireMockServer CLAUDE = new WireMockServer(WireMockConfiguration.options().dynamicPort());
	static final String OK = """
			{"id": "msg_1", "type": "message", "role": "assistant", "model": "claude-sonnet-4-6",
			 "content": [{"type": "text", "text": "Senhor, acolhe a Maria.\\n\\nAmém."}], "stop_reason": "end_turn",
			 "stop_sequence": null, "usage": {"input_tokens": 800, "output_tokens": 120}}""";

	static {
		CLAUDE.start();
	}

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		r.add("clama.oracao.anthropic-url", CLAUDE::baseUrl);
		r.add("clama.oracao.anthropic-api-key", () -> "sk-ant-teste");
	}

	@LocalServerPort int porta;
	@Autowired JdbcTemplate jdbc;
	@Autowired UsuarioRepository usuarios;
	@Autowired PasswordEncoder encoder;
	@Autowired PedidoRepository pedidos;
	@Autowired HmacDeIdentificador hmac;
	@Autowired CaixaDeSaidaEmMemoria caixa;
	@Autowired EnvioAssincrono envio;

	@BeforeEach
	@AfterEach
	void limpar() throws Exception {
		for (int i = 0; i < 500 && envio.pendentes() > 0; i++) Thread.sleep(10);
		CLAUDE.resetAll();
		caixa.limpar();
		jdbc.update(BancoDeTeste.LIMPAR_FILA);
		jdbc.update("delete from auditoria where entidade = 'pedido'");
		jdbc.update("delete from pedido");
		jdbc.update("delete from plano where tipo = 'COMUM'");
		jdbc.update("delete from usuario where email like 'adm-%@exemplo.com'");
	}

	@Test
	void pedidoEnviadoReenviadoSaiDeNovoComChaveNova() throws Exception {
		CLAUDE.stubFor(post("/v1/messages").willReturn(okJson(OK)));
		var plano = jdbc.queryForObject("""
				insert into plano (nome, valor_centavos, complexidade, tipo, ordem) values ('Simples', 3000, 'SIMPLES', 'COMUM', 1)
				returning id""", Long.class);
		var agora = Instant.now();
		var d = new Pedido.Dados(null, plano, null, 3000, "Maria Souza", "maria@exemplo.com", "52998224725", null, (short) 40,
				"feminino", "Pela família", agora, null, "1");
		var id = pedidos.saveAndFlush(Pedido.novo(d, hmac, agora)).getId();
		var email = "adm-" + UUID.randomUUID() + "@exemplo.com";
		Navegador.usuario(usuarios, encoder, email, "senha-forte-123", Papel.ADMIN);
		var adm = new Navegador(porta);
		assertThat(adm.login(email, "senha-forte-123").statusCode()).isEqualTo(200);

		// gratuidade: o pipeline gera e envia
		assertThat(adm.post("/api/admin/pedidos/" + id + "/gratuidade", "").statusCode()).isEqualTo(200);
		await().atMost(Duration.ofSeconds(20)).until(() -> "ENVIADA".equals(status(id)) && caixa.para("maria@exemplo.com").size() == 1);
		assertThat(caixa.para("maria@exemplo.com").get(0).chave()).isEqualTo("oracao/" + id);

		// reenvio: pedido gratuito ja enviado envia de fato, com chave nova, sem chamar a Claude de novo
		var res = adm.post("/api/admin/pedidos/" + id + "/reenvio", "");
		assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
		await().atMost(Duration.ofSeconds(20)).until(() -> caixa.para("maria@exemplo.com").size() == 2);
		var segundo = caixa.para("maria@exemplo.com").get(1);
		assertThat(segundo.chave()).startsWith("oracao/" + id + "/reenvio/").isNotEqualTo("oracao/" + id);
		assertThat(segundo.email().assunto()).isEqualTo("Sua oração chegou, Maria");
		assertThat(segundo.email().texto()).contains("Senhor, acolhe a Maria.");
		assertThat(status(id)).isEqualTo("ENVIADA");

		// outro reenvio: outra chave (nao e deduplicado pela Resend)
		assertThat(adm.post("/api/admin/pedidos/" + id + "/reenvio", "").statusCode()).isEqualTo(200);
		await().atMost(Duration.ofSeconds(20)).until(() -> caixa.para("maria@exemplo.com").size() == 3);
		assertThat(caixa.para("maria@exemplo.com")).extracting(CaixaDeSaidaEmMemoria.Enviado::chave).doesNotHaveDuplicates();
		CLAUDE.verify(1, postRequestedFor(urlEqualTo("/v1/messages")));
		assertThat(jdbc.queryForObject("select count(*) from auditoria where entidade = 'pedido' and entidade_id = ?", Integer.class, id.toString()))
				.isEqualTo(3);
	}

	String status(UUID id) {
		return jdbc.queryForObject("select status from pedido where id = ?", String.class, id);
	}
}
