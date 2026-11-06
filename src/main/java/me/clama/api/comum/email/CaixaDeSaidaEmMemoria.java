package me.clama.api.comum.email;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** EnviadorDeEmail do perfil test (o locmem do Django). */
public class CaixaDeSaidaEmMemoria implements EnviadorDeEmail {
    private final List<Email> enviados = new CopyOnWriteArrayList<>();
    private final Set<String> chaves = ConcurrentHashMap.newKeySet();

    @Override
    public ResultadoEnvio enviar(Email e) {
        // simula a Resend: chave de idempotencia repetida = ja enviado (sem janela de 24 h)
        if (e.chaveDeIdempotencia() != null && !chaves.add(e.chaveDeIdempotencia())) return ResultadoEnvio.JA_ENVIADO;
        enviados.add(e);
        return ResultadoEnvio.ENVIADO;
    }

    public List<Email> enviados() {
        return List.copyOf(enviados);
    }

    public void limpar() {
        enviados.clear();
        chaves.clear();
    }
}
