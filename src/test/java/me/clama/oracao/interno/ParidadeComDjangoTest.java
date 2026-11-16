package me.clama.oracao.interno;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.util.List;
import java.util.UUID;
import me.clama.catalogo.Complexidade;
import me.clama.catalogo.PlanoResumo;
import me.clama.catalogo.TipoDePlano;
import me.clama.pedido.DadosParaOracao;
import me.clama.pedido.StatusPedido;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * O corpo que sai para a Claude e o mesmo que o Django de producao manda. {@code oracao/corpo-django-v6.json} foi gerado
 * com o codigo commitado do clama-backend ({@code build_prompt} + o {@code messages.create} de
 * {@code anthropic_client.gerar_oracao}, pelo SDK Python com transporte falso) e o prompt ativo "Clama Pastoral v6". Aqui
 * passa pelo mesmo caminho do job: prompt padrao, {@link MontagemDoPrompt}, {@link ClienteClaude#gerar} sem documentos e
 * modelo/max_tokens do application.yml.
 */
@WireMockTest
class ParidadeComDjangoTest {

	static final String OK = ClienteClaudeTest.OK;

	@Test
	void corpoIgualAoDoDjango(WireMockRuntimeInfo wm) throws Exception {
		JsonNode fixture;
		try (var in = new ClassPathResource("oracao/corpo-django-v6.json").getInputStream()) {
			fixture = JsonMapper.builder().build().readTree(in);
		}
		var conf = conf();
		assertThat(conf.precos().get(conf.modelo())).as("preco do modelo").isNotNull().matches(OracaoProps.PrecoPorMilhao::completo);
		var cliente = new ClienteClaude(new OracaoProps("sk-ant-teste", wm.getHttpBaseUrl(), conf.modelo(), conf.maxTokens(), conf.timeout(),
				null, 0), d -> {});
		var prompt = new PromptVigente(null).padrao();
		stubFor(post("/v1/messages").willReturn(okJson(OK)));

		var casos = fixture.get("casos");
		assertThat(casos.size()).isEqualTo(4);
		for (int i = 0; i < casos.size(); i++) {
			var caso = casos.get(i);
			var e = caso.get("entrada");
			var idade = e.get("idade").isNull() ? null : (short) e.get("idade").asInt();
			var dados = new DadosParaOracao(UUID.randomUUID(), StatusPedido.GERANDO_ORACAO, 1L, false, e.get("nome").asString(),
					"x@exemplo.com", idade, e.get("sexo").asString(), e.get("pedidoOracao").asString(), null, 0, null);
			var plano = new PlanoResumo(1L, "Plano", null, 3000, Complexidade.valueOf(e.get("complexidade").asString()),
					TipoDePlano.valueOf(e.get("tipo").asString()));

			cliente.gerar(prompt.sistema(), MontagemDoPrompt.mensagem(prompt, dados, plano), List.of());

			var req = findAll(postRequestedFor(urlEqualTo(caso.get("path").asString()))).get(i);
			// Unica diferenca deliberada: o SDK Java 3.x manda anthropic-version 2026-10-21; o Python 0.x do Django, 2023-06-01.
			// Neste corpo nada muda entre as duas (sem temperature/top_p/top_k, sem document no assistant, sem citations).
			assertThat(caso.get("anthropicVersion").asString()).isEqualTo("2023-06-01");
			assertThat(req.getHeader("anthropic-version")).isEqualTo("2026-10-21");
			assertThat(req.containsHeader("anthropic-beta")).isFalse();
			// estrito: nem campo a mais nem a menos, texto exato
			assertThat(equalToJson(caso.get("corpo").toString(), false, false).match(req.getBodyAsString()).isExactMatch())
					.as("caso %d (%s): %s", i, caso.get("planoDjango").asString(), req.getBodyAsString()).isTrue();
		}
	}

	/** {@code clama.oracao.*} como sai do application.yml de producao. */
	static OracaoProps conf() throws Exception {
		var env = new StandardEnvironment();
		new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yml")).forEach(env.getPropertySources()::addLast);
		return Binder.get(env).bind("clama.oracao", OracaoProps.class).get();
	}
}
