package me.clama.virada;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import java.nio.file.Path;
import java.util.UUID;
import me.clama.BancoDeTeste;
import me.clama.EmailDeTeste;
import me.clama.conta.web.Navegador;
import me.clama.freemium.interno.PedidoFreemium;
import me.clama.infra.cripto.Cifra;
import me.clama.infra.seguranca.LimiteDeTaxa;
import me.clama.infra.web.ErroDeNegocio;
import me.clama.pedido.NovoPedido;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Importacao unica do Django com fixture sintetica: regras do par. 4 do mapa da onda 8, login, conta, busca e freemium. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import({ BancoDeTeste.class, EmailDeTeste.class })
@ActiveProfiles({ "test", "importacao" })
class ImportacaoDoDjangoTest {

	static final Path FIXTURE = Path.of("src/test/resources/virada/django-sintetico.json");
	static final String SENHA = "Clama-forte-2026"; // a dos hashes Django da fixture (ConversaoDeSenhaDjangoTest)
	static final String P = "00000000-0000-4000-8000-00000000000";

	@LocalServerPort int porta;
	@Autowired ImportacaoDoDjango importacao;
	@Autowired JdbcTemplate jdbc;
	@Autowired Cifra cifra;
	@Autowired PedidoFreemium freemium;
	@Autowired LimiteDeTaxa limite;

	@BeforeEach
	void destinoComCatalogo() {
		limite.limpar();
		jdbc.update("delete from auditoria");
		jdbc.update("delete from freemium_bloqueio");
		jdbc.update("delete from cobranca");
		jdbc.update("delete from pedido");
		jdbc.update("delete from usuario");
		jdbc.update("delete from plano where tipo = 'COMUM'");
		jdbc.update("delete from instituicao");
		// o que as telas do admin cadastram antes (mapa 4.3.3); Gratuito e Livre vem da V2
		jdbc.update("insert into plano (nome, valor_centavos, complexidade, ordem) values ('Pedido de Oração', 599, 'SIMPLES', 1)");
		jdbc.update("insert into instituicao (nome, ordem) values ('Instituição Sintética', 1)");
	}

	@Test
	void importaComAsRegrasDoMapa() throws Exception {
		var r = importacao.importar(FIXTURE);

		assertThat(r.contagens()).containsEntry("usuarios", 3).containsEntry("pedidos", 9).containsEntry("cobrancas", 2)
				.containsEntry("cobrancas_pagas", 1).containsEntry("freemium_bloqueios", 1).containsEntry("usuarios_sem_senha", 1);
		// P3: o admin vira admin@clama.me com o mesmo hash; nao nasce conta admin@clama.com.br
		assertThat(jdbc.queryForObject("select count(*) from usuario where email = 'admin@clama.com.br'", Long.class)).isZero();
		assertThat(jdbc.queryForMap("select papel, senha from usuario where email = 'admin@clama.me'"))
				.containsEntry("papel", "SUPERUSUARIO")
				.containsEntry("senha", "{argon2}$argon2id$v=19$m=102400,t=2,p=8$eXR6YUpaeTZHQ3BGR0dDQ1JXNmxNRg$/nedzpnCFv184OKLaRq2IP2CN7SflMwURGefQ6lTkeg");
		assertThat(jdbc.queryForMap("select papel, nome_formato_blog from usuario where email = 'fulana@exemplo.com'"))
				.containsEntry("papel", "CLIENTE").containsEntry("nome_formato_blog", "completo");
		assertThat(jdbc.queryForMap("select senha, ativo, nome_completo from usuario where email = 'antigo@exemplo.com'"))
				.containsEntry("senha", null).containsEntry("ativo", false).containsEntry("nome_completo", "Antigo");

		// status: presos viram ERRO pedido_preso; Pix vencido fica aguardando; confirmacao sem token expira
		assertThat(status(1)).isEqualTo("ENVIADA|null|2026-07-20T12:05:00Z|2026-07-20T12:09:00Z");
		assertThat(status(2)).isEqualTo("ENVIADA|null|2026-04-13T12:10:00Z|2026-04-13T12:30:00Z");
		assertThat(status(3)).isEqualTo("AGUARDANDO_PAGAMENTO|null|null|null");
		assertThat(status(5)).isEqualTo("ERRO|pedido_preso|2026-04-13T13:02:00Z|null");
		assertThat(status(6)).isEqualTo("ERRO|pedido_preso|2026-04-29T13:00:00Z|null");
		assertThat(status(7)).isEqualTo("ENVIADA|null|2026-05-13T12:00:00Z|2026-05-13T12:15:00Z");
		assertThat(status(9)).isEqualTo("CONFIRMACAO_EXPIRADA|null|null|null");
		// gratuito: plano GRATUITO, valor 0; pago no plano pelo nome e instituicao pelo nome
		assertThat(jdbc.queryForObject("select p.tipo || '|' || x.valor_centavos from pedido x join plano p on p.id = x.plano_id where x.id = ?",
				String.class, UUID.fromString(P + 7))).isEqualTo("GRATUITO|0");
		assertThat(jdbc.queryForObject("select p.tipo || '|' || (x.instituicao_id is not null) from pedido x join plano p on p.id = x.plano_id where x.id = ?",
				String.class, UUID.fromString(P + 1))).isEqualTo("VALOR_LIVRE|true");
		// PII cifrada com a chave do app; vazio do Django vira nulo
		var c = jdbc.queryForMap("select email, cpf_cnpj, telefone, sexo from pedido where id = ?", UUID.fromString(P + 2));
		assertThat((String) c.get("email")).startsWith("v1:");
		assertThat(cifra.decifrar((String) c.get("cpf_cnpj"))).isEqualTo("52998224725");
		assertThat(c.get("telefone")).isNull();
		assertThat(c.get("sexo")).isNull();

		// trilha do pagamento: cobranca do MP (paga ou nao) e o id do Asaas na auditoria
		assertThat(jdbc.queryForObject("select mp_pagamento_id || '|' || n || '|' || (paga_em is not null) from cobranca where pedido_id = ?",
				String.class, UUID.fromString(P + 1))).isEqualTo("123456789|1|true");
		assertThat(jdbc.queryForObject("select mp_pagamento_id || '|' || (paga_em is not null) || '|' || (expira_em < now()) from cobranca where pedido_id = ?",
				String.class, UUID.fromString(P + 3))).isEqualTo("987654321|false|true");
		assertThat(jdbc.queryForObject("select diff ->> 'pagamento_id' from auditoria where entidade = 'pedido' and entidade_id = ?",
				String.class, P + 2)).isEqualTo("pay_sintetico");

		// login com a senha de sempre (hash do Django) e a "Minha conta" com as oracoes
		var fulana = new Navegador(porta);
		assertThat(fulana.login("fulana@exemplo.com", SENHA).statusCode()).isEqualTo(200);
		var conta = Navegador.json(fulana.get("/api/conta/pedidos"));
		assertThat(conta).hasSize(5);
		assertThat(conta.get(0).get("id").asString()).isEqualTo(P + 3); // mais recente primeiro
		assertThat(conta.findValuesAsString("oracaoGerada")).filteredOn(s -> !s.isEmpty()).containsExactlyInAnyOrder("Senhor, abencoa esta familia.", "Senhor, guia este trabalho.");

		// busca do admin por e-mail (canonico do Gmail) e por CPF: prova os HMACs
		var admin = new Navegador(porta);
		assertThat(admin.login("admin@clama.me", SENHA).statusCode()).isEqualTo(200);
		assertThat(total(admin, "FULANA@exemplo.com")).isEqualTo(5);
		assertThat(total(admin, "529.982.247-25")).isEqualTo(5);
		assertThat(total(admin, "betagama@gmail.com")).isEqualTo(1);
		assertThat(new Navegador(porta).login("admin@clama.com.br", SENHA).statusCode()).isNotEqualTo(200);

		// freemium: quem ganhou a gratuita fica barrado pelo e-mail e pelo CPF (o 8 dividia o CPF do 7: uma linha so)
		assertThat(codigoDoFreemium("beta.gama@gmail.com", "390.533.447-05")).isEqualTo("freemium_ja_usado");
		assertThat(codigoDoFreemium("nova@exemplo.com", "111.444.777-35")).isEqualTo("freemium_ja_usado");
	}

	@Test
	void reaproveitaOAdminCriadoAntesPeloSqlDoMapa() {
		jdbc.update("insert into usuario (email, email_canonico, senha, nome_completo, papel, ativo, criado_em) "
				+ "values ('admin@clama.me', 'admin@clama.me', '{argon2}ja-trocada', 'Admin', 'SUPERUSUARIO', true, now())");

		var r = importacao.importar(FIXTURE);

		assertThat(r.contagens()).containsEntry("admin_reaproveitado", true).containsEntry("usuarios", 3);
		assertThat(jdbc.queryForObject("select senha from usuario where email = 'admin@clama.me'", String.class)).isEqualTo("{argon2}ja-trocada");
		assertThat(jdbc.queryForObject("select count(*) from usuario", Long.class)).isEqualTo(3);
	}

	@Test
	void recusaDestinoComDadosESegundaRodada() {
		importacao.importar(FIXTURE);
		assertThatThrownBy(() -> importacao.importar(FIXTURE)).hasMessageContaining("destino nao esta vazio: pedido");

		destinoComCatalogo();
		jdbc.update("insert into usuario (email, email_canonico, papel, criado_em) values ('x@exemplo.com', 'x@exemplo.com', 'CLIENTE', now())");
		assertThatThrownBy(() -> importacao.importar(FIXTURE)).hasMessageContaining("alem do admin");
	}

	@Test
	void faltandoPlanoNaoGravaNada() {
		jdbc.update("delete from plano where tipo = 'COMUM'");

		assertThatThrownBy(() -> importacao.importar(FIXTURE)).hasMessageContaining("[Pedido de Oração]");

		assertThat(jdbc.queryForObject("select count(*) from usuario", Long.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from pedido", Long.class)).isZero();
	}

	@Test
	void regrasPuras() {
		assertThat(ImportacaoDoDjango.senha("argon2$argon2id$v=19$x")).isEqualTo("{argon2}$argon2id$v=19$x");
		assertThat(ImportacaoDoDjango.senha("pbkdf2_sha256$1$a$b")).isNull();
		assertThat(ImportacaoDoDjango.senha("")).isNull();
		assertThat(ImportacaoDoDjango.email(" Admin@Clama.com.br ")).isEqualTo("admin@clama.me");
		assertThat(ImportacaoDoDjango.status("pago")).isEqualTo("ERRO");
		assertThat(ImportacaoDoDjango.ultimoErro("erro", "ERRO")).isEqualTo("erro_no_django");
		assertThatThrownBy(() -> ImportacaoDoDjango.status("cancelado")).hasMessageContaining("desconhecido");
	}

	private String status(int n) {
		return jdbc.queryForObject("""
				select status || '|' || coalesce(ultimo_erro, 'null') || '|' ||
				  coalesce(to_char(pago_em at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), 'null') || '|' ||
				  coalesce(to_char(enviada_em at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), 'null')
				from pedido where id = ?""", String.class, UUID.fromString(P + n));
	}

	private static long total(Navegador admin, String q) throws Exception {
		var res = admin.get("/api/admin/pedidos?size=100&q=" + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8));
		assertThat(res.statusCode()).isEqualTo(200);
		return Navegador.json(res).get("page").get("totalElements").asLong();
	}

	private String codigoDoFreemium(String email, String cpf) {
		var n = new NovoPedido("Pessoa Nova", email, cpf, null, (short) 30, "feminino", "Pedido", null, null, null, true, "token", null);
		try {
			freemium.submeter(n, "198.51.100.1");
			return "aceito";
		} catch (ErroDeNegocio e) {
			return e.codigo();
		}
	}

}
