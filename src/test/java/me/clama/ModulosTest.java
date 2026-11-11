package me.clama;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import static org.assertj.core.api.Assertions.assertThat;

import me.clama.catalogo.CatalogoApi;
import me.clama.catalogo.Complexidade;
import me.clama.catalogo.InstituicaoResumo;
import me.clama.catalogo.PlanoResumo;
import me.clama.catalogo.TipoDePlano;
import me.clama.conta.ContaApi;
import me.clama.conta.UsuarioResumo;
import me.clama.conta.interno.LoginServico;
import me.clama.conta.interno.UsuarioRepository;
import me.clama.pedido.DadosParaOracao;
import me.clama.pedido.GeracaoSolicitada;
import me.clama.pedido.PedidoApi;
import me.clama.pedido.PedidoCobravel;
import me.clama.pedido.PedidoResumo;
import me.clama.pedido.ReenvioSolicitado;
import me.clama.pedido.StatusPedido;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

class ModulosTest {

	static final ApplicationModules MODULOS = ApplicationModules.of(ClamaApplication.class);

	@Test
	void fronteirasRespeitadas() {
		MODULOS.verify();
	}

	/** Modulo OPEN e ignorado pelo verify(): infra precisa ser folha, entao a regra e explicita. */
	@Test
	void infraEFolha() {
		var classes = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
				.importPackages("me.clama");
		ArchRuleDefinition.noClasses().that().resideInAPackage("me.clama.infra..")
				.should().dependOnClassesThat(DescribedPredicate.describe("estao em me.clama fora de infra",
						(JavaClass c) -> c.getPackageName().startsWith("me.clama")
								&& !c.getPackageName().startsWith("me.clama.infra")
								&& !c.getName().equals("me.clama.ClamaApplication")))
				.check(classes);
	}

	/**
	 * O conta so mostra ContaApi e UsuarioResumo; o resto mora em conta.interno e conta.web. O ApplicationModules ignora
	 * classes de teste, entao um modulo ficticio nao quebraria o verify(): a prova e perguntar ao Modulith o que e exposto.
	 */
	@Test
	void contaExpoeSoContaApiEUsuarioResumo() {
		var conta = MODULOS.getModuleByName("conta").orElseThrow();
		assertThat(conta.isExposed(ContaApi.class)).isTrue();
		assertThat(conta.isExposed(UsuarioResumo.class)).isTrue();
		assertThat(conta.isExposed(UsuarioRepository.class)).isFalse();
		assertThat(conta.isExposed(LoginServico.class)).isFalse();
	}

	@Test
	void catalogoExpoeSoApiEResumos() {
		var catalogo = MODULOS.getModuleByName("catalogo").orElseThrow();
		for (var c : new Class<?>[] { CatalogoApi.class, PlanoResumo.class, InstituicaoResumo.class, Complexidade.class,
				TipoDePlano.class })
			assertThat(catalogo.isExposed(c)).as(c.getSimpleName()).isTrue();
		assertThat(catalogo.isExposed(me.clama.catalogo.interno.PlanoRepository.class)).isFalse();
		assertThat(catalogo.isExposed(me.clama.catalogo.interno.Plano.class)).isFalse();
	}

	@Test
	void pedidoExpoeSoApiEventosERecords() {
		var pedido = MODULOS.getModuleByName("pedido").orElseThrow();
		for (var c : new Class<?>[] { PedidoApi.class, PedidoResumo.class, PedidoCobravel.class, DadosParaOracao.class,
				StatusPedido.class, GeracaoSolicitada.class, ReenvioSolicitado.class, me.clama.pedido.NovoPedido.class,
				me.clama.pedido.Identificadores.class })
			assertThat(pedido.isExposed(c)).as(c.getSimpleName()).isTrue();
		assertThat(pedido.isExposed(me.clama.pedido.interno.Pedido.class)).isFalse();
		assertThat(pedido.isExposed(me.clama.pedido.interno.PedidoRepository.class)).isFalse();
	}

	/** Pedido so enxerga conta e catalogo: nao conhece pagamento nem oracao (que dependem dele). */
	@Test
	void pedidoNaoDependeDePagamentoNemDeOracao() {
		var pedido = MODULOS.getModuleByName("pedido").orElseThrow();
		var deps = pedido.getDirectDependencies(MODULOS).stream().map(d -> d.getTargetModule().getIdentifier().toString()).toList();
		assertThat(deps).doesNotContain("pagamento", "oracao");
	}

	@Test
	void pagamentoExpoeSoPagamentoApi() {
		var pagamento = MODULOS.getModuleByName("pagamento").orElseThrow();
		assertThat(pagamento.isExposed(me.clama.pagamento.PagamentoApi.class)).isTrue();
		assertThat(pagamento.isExposed(me.clama.pagamento.interno.Cobrancas.class)).isFalse();
	}

	/** O sentido e pagamento -> pedido; o contrario faria ciclo (a regra acima so passa a valer com o modulo existindo). */
	@Test
	void pagamentoDependeDePedidoENaoOContrario() {
		var pagamento = MODULOS.getModuleByName("pagamento").orElseThrow();
		assertThat(pagamento.getDirectDependencies(MODULOS).stream().map(d -> d.getTargetModule().getIdentifier().toString()))
				.contains("pedido");
		var pedido = MODULOS.getModuleByName("pedido").orElseThrow();
		assertThat(pedido.getDirectDependencies(MODULOS).stream().map(d -> d.getTargetModule().getIdentifier().toString()))
				.doesNotContain("pagamento");
	}

	/** oracao usa pedido e catalogo; nenhum dos dois a conhece. */
	@Test
	void oracaoDependeDePedidoECatalogoENaoOContrario() {
		var oracao = MODULOS.getModuleByName("oracao").orElseThrow();
		assertThat(oracao.getDirectDependencies(MODULOS).stream().map(d -> d.getTargetModule().getIdentifier().toString()))
				.contains("pedido", "catalogo");
		for (var nome : new String[] { "pedido", "catalogo" }) {
			var m = MODULOS.getModuleByName(nome).orElseThrow();
			assertThat(m.getDirectDependencies(MODULOS).stream().map(d -> d.getTargetModule().getIdentifier().toString()))
					.as(nome).doesNotContain("oracao");
		}
	}

	/** Pagamento e infra ficam abaixo da oracao: nenhum dos dois a conhece. */
	@Test
	void pagamentoEInfraNaoDependemDeOracao() {
		var pagamento = MODULOS.getModuleByName("pagamento").orElseThrow();
		assertThat(pagamento.getDirectDependencies(MODULOS).stream().map(d -> d.getTargetModule().getIdentifier().toString()))
				.doesNotContain("oracao");
		var classes = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests()).importPackages("me.clama");
		ArchRuleDefinition.noClasses().that().resideInAPackage("me.clama.infra..").should().dependOnClassesThat()
				.resideInAPackage("me.clama.oracao..").check(classes);
	}

	/** freemium usa pedido, conta e catalogo; ninguem o conhece. */
	@Test
	void freemiumDependeDePedidoContaECatalogoENinguemDependeDele() {
		var freemium = MODULOS.getModuleByName("freemium").orElseThrow();
		assertThat(freemium.getDirectDependencies(MODULOS).stream().map(d -> d.getTargetModule().getIdentifier().toString()))
				.contains("pedido", "conta", "catalogo");
		MODULOS.stream().filter(m -> !m.getIdentifier().toString().equals("freemium")).forEach(m ->
				assertThat(m.getDirectDependencies(MODULOS).stream().map(d -> d.getTargetModule().getIdentifier().toString()))
						.as(m.getIdentifier().toString()).doesNotContain("freemium"));
		var classes = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests()).importPackages("me.clama");
		ArchRuleDefinition.noClasses().that().resideInAPackage("me.clama.infra..").should().dependOnClassesThat()
				.resideInAPackage("me.clama.freemium..").check(classes);
	}

	/** As tabelas de pedido e usuario sao privadas: o freemium so passa por PedidoApi e ContaApi, e nao escreve SQL nelas. */
	@Test
	void freemiumNaoLeNemEscreveATabelaDePedidoNemDeUsuario() throws Exception {
		var classes = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests()).importPackages("me.clama");
		ArchRuleDefinition.noClasses().that().resideInAPackage("me.clama.freemium..").should().dependOnClassesThat()
				.resideInAnyPackage("me.clama.pedido.interno..", "me.clama.conta.interno..").check(classes);
		var sql = java.util.regex.Pattern.compile("(?i)\\b(from|join|into|update|delete\\s+from)\\s+(pedido|usuario)\\b");
		try (var arquivos = java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java/me/clama/freemium"))) {
			for (var f : arquivos.filter(p -> p.toString().endsWith(".java")).toList()) {
				assertThat(sql.matcher(java.nio.file.Files.readString(f)).find()).as(f.toString()).isFalse();
			}
		}
	}

	@Test
	void documentacao() {
		new Documenter(MODULOS).writeDocumentation();
	}
}
