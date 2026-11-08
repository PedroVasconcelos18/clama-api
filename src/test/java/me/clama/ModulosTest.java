package me.clama;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
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

	@Test
	void documentacao() {
		new Documenter(MODULOS).writeDocumentation();
	}
}
