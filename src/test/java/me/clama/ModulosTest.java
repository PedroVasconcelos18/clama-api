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
	void documentacao() {
		new Documenter(MODULOS).writeDocumentation();
	}
}
