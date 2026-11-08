package me.clama;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

class ModulosTest {

	static final ApplicationModules MODULOS = ApplicationModules.of(ClamaApplication.class);

	@Test
	void fronteirasRespeitadas() {
		MODULOS.verify();
	}

	@Test
	void documentacao() {
		new Documenter(MODULOS).writeDocumentation();
	}
}
