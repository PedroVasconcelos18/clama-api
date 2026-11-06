package me.clama.api.pedido.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import me.clama.api.BancoDeTeste;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.senha.SenhaDjango;
import me.clama.api.identidade.token.ServicoDeTokens;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** HTTP e dados minimos dos testes de POST /api/pedidos/ e /api/doacoes/ (catalogo com ids fixos, upsert). */
class ApiDePedidos {
    static final String P1 = "55555555-5555-4555-8555-000000000001";
    static final String P_INATIVO = "55555555-5555-4555-8555-000000000004";
    static final String I1 = "66666666-6666-4666-8666-000000000001";
    static final ObjectMapper JSON = new ObjectMapper();

    private final int porta;
    private final HttpClient http = HttpClient.newHttpClient();

    ApiDePedidos(int porta) {
        this.porta = porta;
    }

    /** O banco do contexto e compartilhado com outras classes: nada de TRUNCATE, so garante o que o teste precisa. */
    static void semearCatalogo(JdbcTemplate jdbc) {
        String plano = """
                insert into plans_plan (created_at, updated_at, id, nome, valor_centavos, descricao, complexidade, ordem,
                    ativo, visivel) values (now(), now(), ?::uuid, ?, ?, 'd', 'simples', ?, ?, true)
                on conflict (id) do update set ativo = excluded.ativo, visivel = true""";
        jdbc.update(plano, P1, "Pedido de Oração", 2000, 1, true);
        jdbc.update(plano, P_INATIVO, "Plano Antigo", 1500, 0, false);
        jdbc.update("""
                insert into instituicoes_instituicao (created_at, updated_at, id, nome, ativo, ordem, logo)
                values (now(), now(), ?::uuid, 'Lar Esperança', true, 1, '') on conflict (id) do update set ativo = true""",
                I1);
    }

    /** Os pedidos criados prendem usuario/instituicao por FK: as outras classes do contexto apagam esses. */
    static void apagarPedidos(JdbcTemplate jdbc) {
        BancoDeTeste.exigirContainer(jdbc);
        jdbc.update("delete from orders_pedido");
    }

    /** Cookies (access + csrftoken) e o token do header de um usuario recem-criado. */
    record Sessao(String cookies, String csrf) {}

    Sessao entrar(UsuarioRepository usuarios, ServicoDeTokens tokens, String email, boolean forcarTroca)
            throws Exception {
        Usuario u = new Usuario();
        u.setEmail(email);
        u.setPassword(new SenhaDjango().encode("Clama-forte-2026"));
        u.setForceChangePassword(forcarTroca);
        return sessao(tokens.emitirPar(usuarios.saveAndFlush(u)).access());
    }

    Sessao sessao(String access) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri("/api/csrf/")).build(),
                HttpResponse.BodyHandlers.ofString());
        String csrf = (String) JSON.readValue(r.body(), Map.class).get("csrf_token");
        return new Sessao("clama_access=" + access + "; csrftoken=" + csrf, csrf);
    }

    HttpResponse<String> post(String path, String corpo, Sessao s, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.ofString(corpo))
                .header("Content-Type", "application/json");
        if (s != null) {
            b.header("Cookie", s.cookies()).header("X-CSRFToken", s.csrf());
        }
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + porta + path);
    }
}
