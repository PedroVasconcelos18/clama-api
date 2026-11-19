package me.clama.freemium.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import me.clama.BancoDeTeste;
import me.clama.EmailDeTeste;
import me.clama.conta.interno.Papel;
import me.clama.conta.interno.UsuarioRepository;
import me.clama.conta.web.Navegador;
import me.clama.freemium.interno.Bloqueios;
import me.clama.freemium.interno.PedidoFreemium;
import me.clama.infra.cripto.HmacDeIdentificador;
import me.clama.infra.seguranca.LimiteDeTaxa;
import me.clama.infra.web.ErroDeNegocio;
import me.clama.pedido.NovoPedido;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;

/** Admin de bloqueios do freemium: lista com hash cortado, busca pelo valor em claro, desbloqueio auditado sem hash. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import({ BancoDeTeste.class, EmailDeTeste.class })
@ExtendWith(OutputCaptureExtension.class)
class BloqueiosAdminTest {

	static final String BASE = "/api/admin/freemium/bloqueios";
	static final String CPF = "529.982.247-25";
	static final String CPF_DIGITOS = "52998224725";

	@LocalServerPort int porta;
	@Autowired JdbcTemplate jdbc;
	@Autowired UsuarioRepository usuarios;
	@Autowired PasswordEncoder encoder;
	@Autowired LimiteDeTaxa limite;
	@Autowired HmacDeIdentificador hmac;
	@Autowired Bloqueios bloqueios;
	@Autowired PedidoFreemium servico;

	@BeforeEach
	@AfterEach
	void limpar() {
		limite.limpar();
		jdbc.update(BancoDeTeste.LIMPAR_FILA);
		jdbc.update("delete from pedido");
		jdbc.update("delete from freemium_bloqueio");
		jdbc.update("delete from auditoria where entidade = 'freemium_bloqueio'");
		jdbc.update("delete from usuario where email like 'adm-%@exemplo.com'");
		jdbc.update("""
				insert into plano (nome, descricao, valor_centavos, complexidade, tipo, ordem)
				values ('Gratuito', 'Sua primeira oração — por nossa conta', 0, 'SIMPLES', 'GRATUITO', 1000)
				on conflict (tipo) where tipo <> 'COMUM' do nothing""");
	}

	Navegador entrar(Papel papel) throws Exception {
		var email = "adm-" + UUID.randomUUID() + "@exemplo.com";
		Navegador.usuario(usuarios, encoder, email, "senha-forte-123", papel);
		var nav = new Navegador(porta);
		assertThat(nav.login(email, "senha-forte-123").statusCode()).isEqualTo(200);
		return nav;
	}

	long bloquear(String email, String cpf, String ip) {
		assertThat(bloqueios.inserir(hmac.email(email), hmac.cpf(cpf), hmac.ip(ip), Instant.now())).isTrue();
		return jdbc.queryForObject("select id from freemium_bloqueio where email_hmac = ?", Long.class, hmac.email(email));
	}

	static String corpo(String email, String cpf) {
		return "{" + (email == null ? "" : "\"email\":\"" + email + "\"") + (email != null && cpf != null ? "," : "")
				+ (cpf == null ? "" : "\"cpfCnpj\":\"" + cpf + "\"") + "}";
	}

	@Test
	void listaPaginadaComHashCortadoNuncaInteiroEMaisNovoPrimeiro() throws Exception {
		var velho = bloquear("a@exemplo.com", "111.444.777-35", null);
		Thread.sleep(5);
		var novo = bloquear("b@exemplo.com", CPF, "203.0.113.9");
		var res = entrar(Papel.ADMIN).get(BASE);

		assertThat(res.statusCode()).isEqualTo(200);
		JsonNode j = Navegador.json(res);
		assertThat(j.get("page").get("totalElements").asInt()).isEqualTo(2);
		assertThat(j.get("page").get("size").asInt()).isEqualTo(20);
		var c = j.get("content");
		assertThat(c.get(0).get("id").asLong()).isEqualTo(novo);
		assertThat(c.get(1).get("id").asLong()).isEqualTo(velho);
		assertThat(c.get(0).get("temIp").asBoolean()).isTrue();
		assertThat(c.get(1).get("temIp").asBoolean()).isFalse();
		var curto = c.get(0).get("emailHmacCurto").asString();
		assertThat(curto).hasSize(13).endsWith("…").startsWith(hmac.email("b@exemplo.com").substring(0, 12));
		assertThat(c.get(0).get("cpfHmacCurto").asString()).hasSize(13).endsWith("…");
		assertThat(c.get(0).get("criadoEm").asString()).endsWith("Z");
		assertThat(res.body()).doesNotContain(hmac.email("b@exemplo.com")).doesNotContain(hmac.cpf(CPF)).doesNotContain("ipHmac");
	}

	@Test
	void buscaPorEmailComAliasDoGmailEPorCpfComMascara() throws Exception {
		var id = bloquear("maria.souza@gmail.com", CPF, null);
		bloquear("outra@exemplo.com", "111.444.777-35", null);
		var adm = entrar(Papel.ADMIN);

		var porEmail = adm.post(BASE + "/busca", corpo("Maria.Souza+tag@googlemail.com", null));
		assertThat(porEmail.statusCode()).isEqualTo(200);
		assertThat(Navegador.json(porEmail).size()).isOne();
		assertThat(Navegador.json(porEmail).get(0).get("id").asLong()).isEqualTo(id);

		var porCpf = adm.post(BASE + "/busca", corpo(null, "529.982.247-25"));
		assertThat(Navegador.json(porCpf).size()).isOne();
		assertThat(Navegador.json(porCpf).get(0).get("id").asLong()).isEqualTo(id);

		var nenhum = adm.post(BASE + "/busca", corpo("ninguem@exemplo.com", null));
		assertThat(nenhum.statusCode()).isEqualTo(200);
		assertThat(Navegador.json(nenhum).size()).isZero();
		assertThat(porEmail.body()).doesNotContain(hmac.email("maria.souza@gmail.com"));
	}

	@Test
	void corpoVazioOuSoComBrancosE400ECamposGrandesDemaisTambem() throws Exception {
		var adm = entrar(Papel.ADMIN);
		for (var corpo : List.of("{}", "{\"email\":\"  \",\"cpfCnpj\":\"\"}", "{\"cpfCnpj\":\"abc\"}")) {
			var res = adm.post(BASE + "/busca", corpo);
			assertThat(res.statusCode()).as(corpo).isEqualTo(400);
			assertThat(Navegador.json(res).get("codigo").asString()).isEqualTo("dados_invalidos");
		}
		assertThat(adm.post(BASE + "/busca", corpo("a".repeat(300) + "@exemplo.com", null)).statusCode()).isEqualTo(400);
		assertThat(adm.post(BASE + "/busca", corpo(null, "1".repeat(31))).statusCode()).isEqualTo(400);
	}

	@Test
	void desbloquearApagaGravaAuditoriaSemHashEAPessoaPodeSubmeterDeNovo() throws Exception {
		var email = "frm-" + UUID.randomUUID() + "@exemplo.com";
		var id = bloquear(email, CPF, null);
		var novo = new NovoPedido("Maria Souza", email, CPF, null, null, null, "Pela família", null, null, null, true, null, null);
		assertThatThrownBy(() -> servico.submeter(novo, null)).isInstanceOfSatisfying(ErroDeNegocio.class,
				e -> assertThat(e.codigo()).isEqualTo("freemium_ja_usado"));

		var adm = entrar(Papel.ADMIN);
		var admId = jdbc.queryForObject("select id from usuario where email like 'adm-%' order by id desc limit 1", Long.class);
		var res = adm.delete(BASE + "/" + id);
		assertThat(res.statusCode()).isEqualTo(204);

		var linhas = jdbc.queryForList("select autor_id, acao, entidade_id, diff::text as diff from auditoria where entidade = 'freemium_bloqueio'");
		assertThat(linhas).hasSize(1);
		var a = linhas.get(0);
		assertThat(a.get("acao")).isEqualTo("EXCLUSAO");
		assertThat(a.get("entidade_id")).isEqualTo(String.valueOf(id));
		assertThat(((Number) a.get("autor_id")).longValue()).isEqualTo(admId);
		assertThat(a.get("diff").toString()).isEqualTo("{}").doesNotContain(hmac.email(email)).doesNotContain(hmac.cpf(CPF));
		assertThat(servico.submeter(novo, null)).isNotNull();
	}

	@Test
	void idInexistenteE404ComTextoPastoral() throws Exception {
		var res = entrar(Papel.ADMIN).delete(BASE + "/999999");
		assertThat(res.statusCode()).isEqualTo(404);
		assertThat(Navegador.json(res).get("mensagemPastoral").asString()).isEqualTo("Esse bloqueio não foi encontrado.");
		assertThat(jdbc.queryForObject("select count(*) from auditoria where entidade = 'freemium_bloqueio'", Integer.class)).isZero();
	}

	@Test
	void anonimoRecebe401EClienteRecebe403() throws Exception {
		var id = bloquear("a@exemplo.com", CPF, null);
		var anonimo = new Navegador(porta);
		anonimo.get("/api/auth/eu");
		var cliente = entrar(Papel.CLIENTE);
		for (var nav : List.of(anonimo, cliente)) {
			var esperado = nav == anonimo ? 401 : 403;
			assertThat(nav.get(BASE).statusCode()).isEqualTo(esperado);
			assertThat(nav.post(BASE + "/busca", corpo("a@exemplo.com", null)).statusCode()).isEqualTo(esperado);
			assertThat(nav.delete(BASE + "/" + id).statusCode()).isEqualTo(esperado);
		}
		assertThat(jdbc.queryForObject("select count(*) from freemium_bloqueio", Integer.class)).isOne();
	}

	@Test
	void ologNaoLevaEmailNemCpfDaBusca(CapturedOutput saida) throws Exception {
		var email = "frm-" + UUID.randomUUID() + "@exemplo.com";
		bloquear(email, CPF, null);
		var adm = entrar(Papel.ADMIN);
		adm.post(BASE + "/busca", corpo(email, CPF));
		adm.post(BASE + "/busca", corpo(null, "{}"));
		adm.post(BASE + "/busca", corpo(email.toUpperCase(), "529.982.247-25"));
		assertThat(saida.getAll()).doesNotContain(email).doesNotContain(email.toUpperCase()).doesNotContain(CPF).doesNotContain(CPF_DIGITOS)
				.doesNotContain(hmac.email(email)).doesNotContain(hmac.cpf(CPF));
	}
}
