package me.clama.api.comum.email;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** EnviadorDeEmail do perfil test (o locmem do Django). */
public class CaixaDeSaidaEmMemoria implements EnviadorDeEmail {
    private final List<Email> enviados = new CopyOnWriteArrayList<>();

    @Override
    public void enviar(Email e) {
        enviados.add(e);
    }

    public List<Email> enviados() {
        return List.copyOf(enviados);
    }

    public void limpar() {
        enviados.clear();
    }
}
