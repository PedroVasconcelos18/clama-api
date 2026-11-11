package me.clama;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import me.clama.infra.email.Email;
import me.clama.infra.email.EnviadorDeEmail;

/** EnviadorDeEmail dos testes: guarda o que sairia pela Resend; {@link #falhar} simula a Resend fora do ar. */
public class CaixaDeSaidaEmMemoria implements EnviadorDeEmail {

	public record Enviado(Email email, String chave) {}

	private final List<Enviado> enviados = new CopyOnWriteArrayList<>();
	private volatile boolean falhando;
	private volatile Predicate<Email> recusa = e -> false;
	private volatile long atrasoMs;

	@Override
	public void enviar(Email email, String chaveIdempotencia) {
		if (atrasoMs > 0) {
			try { Thread.sleep(atrasoMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
		}
		if (falhando || recusa.test(email)) throw new IllegalStateException("Resend fora do ar (teste)");
		enviados.add(new Enviado(email, chaveIdempotencia));
	}

	public List<Enviado> enviados() { return List.copyOf(enviados); }

	public List<Enviado> para(String destinatario) {
		return enviados.stream().filter(e -> e.email().para().equals(destinatario)).toList();
	}

	public void falhar(boolean falhando) { this.falhando = falhando; }

	/** Falha so os e-mails que casam (ex.: a oracao falha e o alerta ao admin sai). */
	public void recusar(Predicate<Email> recusa) { this.recusa = recusa; }

	/** Simula a Resend lenta: cada envio demora isto. */
	public void atrasar(long ms) { atrasoMs = ms; }

	/** O envio e assincrono: espera (ate 5 s) chegarem {@code n} e-mails para o destinatario. */
	public List<Enviado> aguardarPara(String destinatario, int n) {
		var prazo = System.nanoTime() + 5_000_000_000L;
		while (para(destinatario).size() < n && System.nanoTime() < prazo) {
			try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
		}
		return para(destinatario);
	}

	public void limpar() {
		enviados.clear();
		falhando = false;
		recusa = e -> false;
		atrasoMs = 0;
	}
}
