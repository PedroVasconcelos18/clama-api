package me.clama.virada;

import java.nio.file.Path;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * So existe com o perfil {@code importacao} E {@code clama.importacao.arquivo} definido (as duas coisas de proposito, no
 * terminal do operador; nenhuma das duas existe no Railway). Importa, imprime as contagens e encerra o processo antes de
 * a aplicacao ficar pronta (a fila nem sobe). Erro: o boot falha com codigo 1 e nada e gravado (uma transacao).
 */
@Component
@Profile("importacao")
@ConditionalOnProperty("clama.importacao.arquivo")
class ExecucaoDaImportacao implements ApplicationRunner {

	private final ImportacaoDoDjango importacao;
	private final ConfigurableApplicationContext contexto;
	private final String arquivo;

	ExecucaoDaImportacao(ImportacaoDoDjango importacao, ConfigurableApplicationContext contexto,
			@org.springframework.beans.factory.annotation.Value("${clama.importacao.arquivo}") String arquivo) {
		this.importacao = importacao;
		this.contexto = contexto;
		this.arquivo = arquivo;
	}

	@Override
	public void run(ApplicationArguments args) {
		System.out.println(importacao.importar(Path.of(arquivo)));
		System.exit(SpringApplication.exit(contexto, () -> 0));
	}
}
