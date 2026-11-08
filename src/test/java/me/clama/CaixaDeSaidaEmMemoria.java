package me.clama;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import me.clama.infra.email.Email;
import me.clama.infra.email.EnviadorDeEmail;

/** EnviadorDeEmail dos testes: guarda o que sairia pela Resend; {@link #falhar} simula a Resend fora do ar. */
public class CaixaDeSaidaEmMemoria implements EnviadorDeEmail {

	public record Enviado(Email email, String chave) {}

	private final List<Enviado> enviados = new CopyOnWriteArrayList<>();
	private volatile boolean falhando;

	@Override
	public void enviar(Email email, String chaveIdempotencia) {
		if (falhando) throw new IllegalStateException("Resend fora do ar (teste)");
		enviados.add(new Enviado(email, chaveIdempotencia));
	}

	public List<Enviado> enviados() { return List.copyOf(enviados); }

	public List<Enviado> para(String destinatario) {
		return enviados.stream().filter(e -> e.email().para().equals(destinatario)).toList();
	}

	public void falhar(boolean falhando) { this.falhando = falhando; }

	public void limpar() {
		enviados.clear();
		falhando = false;
	}
}
