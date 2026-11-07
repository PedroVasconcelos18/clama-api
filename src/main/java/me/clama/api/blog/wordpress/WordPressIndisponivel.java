package me.clama.api.blog.wordpress;

import me.clama.api.comum.erro.PastoralException;

/**
 * Nao deu para perguntar ao WordPress (rede, 4xx/5xx, corpo invalido). Nunca e "o post nao existe". R12: a mensagem e
 * fixa; o texto da causa (host e porta) fica so no log.
 */
public class WordPressIndisponivel extends PastoralException {
    public WordPressIndisponivel(Throwable causa) {
        super(400, "wordpress_indisponivel", "Não foi possível ler a API do WordPress.",
                "Estamos com dificuldade de falar com o blog agora.");
        if (causa != null) initCause(causa);
    }
}
