package me.clama.api;

import me.clama.api.comum.comando.Comandos;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ClamaApiApplication {

	public static void main(String[] args) {
		if (Comandos.ehComando(args)) {
			System.exit(Comandos.rodar(args)); // modo comando (mapa §9): nunca sobe o web
		}
		SpringApplication.run(ClamaApiApplication.class, args);
	}

}
