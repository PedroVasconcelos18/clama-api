package me.clama.api.identidade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import me.clama.api.BancoDeTeste;
import me.clama.api.comum.erro.PastoralException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ExclusaoDeUsuario (mapa 7 §4.3): a paridade com o delete do ORM do Django, tabela por tabela. Cada chamada roda numa
 * transacao que comita, entao as FKs DEFERRABLE INITIALLY DEFERRED sao conferidas de verdade: uma FK esquecida faz o
 * commit falhar.
 */
@SpringBootTest
@Import(BancoDeTeste.class)
@ActiveProfiles("test")
class ExclusaoDeUsuarioTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired UsuarioRepository usuarios;
    @Autowired ExclusaoDeUsuario exclusao;
    @Autowired TransactionTemplate tx;

    long admin;
    UUID espelho;

    /** Antes e depois: o que sobra (bans, blog) quebraria o "delete from users_user" da limpeza de outras classes. */
    @org.junit.jupiter.api.AfterEach
    void truncar() {
        BancoDeTeste.exigirContainer(jdbc);
        // tokens freemium antes de pedidos; CASCADE leva blog, tokens JWT, grupos e django_admin_log
        jdbc.execute("TRUNCATE freemium_freemiumconfirmationtoken, instituicoes_repasse, payments_webhookevento, "
                + "orders_pedido, plans_plan, instituicoes_instituicao, blog_postespelho, blog_post, users_user, "
                + "auth_group, auth_permission CASCADE");
    }

    @BeforeEach
    void limpar() {
        truncar();
        admin = usuario("admin-exclusao@exemplo.com", true, false);
        espelho = UUID.randomUUID();
        jdbc.update("""
                insert into blog_postespelho (id, created_at, updated_at, wp_post_id, slug, titulo, status, url)
                values (?, now(), now(), 1, 'p', 'P', 'publish', 'https://clama.me/blog/p')""", espelho);
    }

    long usuario(String email, boolean admin, boolean superuser) {
        Usuario u = new Usuario();
        u.setEmail(email);
        u.setPassword("x");
        u.setClamaAdmin(admin);
        u.setStaff(admin);
        u.setSuperuser(superuser);
        return usuarios.saveAndFlush(u).getId();
    }

    int contar(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    UUID pedido(long user) {
        UUID plano = UUID.randomUUID();
        UUID instituicao = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into plans_plan (created_at, updated_at, id, nome, valor_centavos, descricao, complexidade, ordem,
                    ativo, visivel) values (now(), now(), ?, 'P', 2000, 'd', 'simples', 1, true, true)""", plano);
        jdbc.update("""
                insert into instituicoes_instituicao (created_at, updated_at, id, nome, logo, ativo, ordem)
                values (now(), now(), ?, 'Lar', '', true, 1)""", instituicao);
        jdbc.update("""
                insert into orders_pedido (created_at, updated_at, id, nome, email, telefone, cpf_cnpj, idade, sexo,
                    pedido_oracao, oracao_gerada, valor_centavos, canal_entrega, status, plano_id, instituicao_id,
                    consent_aceito, consent_versao, last_error, retry_count, whatsapp_message_id, eh_gratuito,
                    device_hash, provider_payment_id, user_id)
                values (now(), now(), ?, 'Maria', 'maria@exemplo.com', '', '52998224725', 35, 'feminino', 'Peço.', '',
                    2000, 'email', 'pago', ?, ?, true, '1', '', 0, '', false, '', '', ?)""", id, plano, instituicao,
                user);
        jdbc.update("""
                insert into freemium_freemiumconfirmationtoken (created_at, updated_at, id, token, expires_at, used_at,
                    ip_origem, device_hash, pedido_id)
                values (now(), now(), gen_random_uuid(), ?, now() + interval '1 day', null, null, '', ?)""",
                "tok-" + id, id);
        jdbc.update("""
                insert into instituicoes_repasse (created_at, updated_at, id, pedido_id, instituicao_id,
                    valor_base_centavos, percentual, valor_centavos, status, transferido_em, comprovante)
                values (now(), now(), gen_random_uuid(), ?, ?, 2000, 20, 400, 'registrado', null, '')""", id,
                instituicao);
        jdbc.update("""
                insert into payments_webhookevento (created_at, updated_at, id, provider, external_event_id, event_type,
                    payload, status, error_message, pedido_id)
                values (now(), now(), gen_random_uuid(), 'MERCADO_PAGO', ?, 'payment', '{}'::jsonb, 'PROCESSADO', '',
                    ?)""", "ev-" + id, id);
        return id;
    }

    void comentarioEReacao(long user) {
        jdbc.update("""
                insert into blog_comentario (id, created_at, updated_at, customer_id, post_espelho_id, conteudo,
                    ip_address, is_suspeito) values (gen_random_uuid(), now(), now(), ?, ?, 'Amém', '', false)""", user,
                espelho);
        jdbc.update("""
                insert into blog_reacao (id, created_at, updated_at, tipo, customer_id, post_espelho_id)
                values (gen_random_uuid(), now(), now(), 'like', ?, ?)""", user, espelho);
    }

    UUID ban(long customer, long banidoPor, Long revogadoPor) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into blog_customerbanido (created_at, updated_at, id, motivo, banido_em, revogado_em,
                    banido_por_id, customer_id, revogado_por_id)
                values (now(), now(), ?, 'Ofensa', now(), case when ?::bigint is null then null else now() end, ?, ?,
                    ?)""", id, revogadoPor, banidoPor, customer, revogadoPor);
        return id;
    }

    void excluir(long id) {
        tx.executeWithoutResult(s -> assertThat(exclusao.excluir(id)).isTrue());
    }

    String codigo(Runnable r) {
        try {
            tx.executeWithoutResult(s -> r.run());
        } catch (PastoralException e) {
            assertThat(e.status()).isEqualTo(409);
            return (String) ((java.util.Map<?, ?>) e.corpo().get("error")).get("code");
        }
        throw new AssertionError("esperava 409");
    }

    @Test
    void exclusaoCompletaNaOrdem() {
        long alvo = usuario("alvo@exemplo.com", false, false);
        long outro = usuario("outro@exemplo.com", false, false);
        UUID pedido = pedido(alvo);
        comentarioEReacao(alvo);
        comentarioEReacao(outro);
        ban(alvo, admin, null);
        UUID banDoOutro = ban(outro, admin, alvo); // alvo revogou um ban (como admin, no passado)
        jdbc.update("""
                insert into token_blacklist_outstandingtoken (token, created_at, expires_at, user_id, jti)
                values ('t1', now(), now() + interval '7 days', ?, 'jti-1'),
                       ('t2', now(), now() + interval '7 days', ?, 'jti-2')""", alvo, alvo);
        jdbc.update("""
                insert into token_blacklist_blacklistedtoken (blacklisted_at, token_id)
                select now(), id from token_blacklist_outstandingtoken where jti = 'jti-1'""");
        jdbc.update("insert into auth_group (name) values ('g')");
        jdbc.update("insert into django_content_type (app_label, model) values ('users', 'user') on conflict do nothing");
        jdbc.update("""
                insert into auth_permission (name, content_type_id, codename)
                select 'p', id, 'teste_exclusao' from django_content_type where app_label = 'users' and model = 'user'
                on conflict do nothing""");
        jdbc.update("insert into users_user_groups (user_id, group_id) select ?, id from auth_group", alvo);
        jdbc.update("""
                insert into users_user_user_permissions (user_id, permission_id)
                select ?, id from auth_permission where codename = 'teste_exclusao'""", alvo);
        jdbc.update("""
                insert into django_admin_log (action_time, object_id, object_repr, action_flag, change_message,
                    content_type_id, user_id) values (now(), '1', '1', 2, '', null, ?), (now(), '1', '1', 2, '', null, ?)""",
                alvo, admin);

        excluir(alvo);

        assertThat(contar("select count(*) from users_user where id = ?", alvo)).isZero();
        // SET_NULL: o pedido fica, sem vinculo; o que pende do pedido nao muda (nada de CASCADE/PROTECT alcancado)
        assertThat(jdbc.queryForObject("select user_id from orders_pedido where id = ?", Long.class, pedido)).isNull();
        assertThat(contar("select count(*) from freemium_freemiumconfirmationtoken where pedido_id = ?", pedido)).isOne();
        assertThat(contar("select count(*) from instituicoes_repasse where pedido_id = ?", pedido)).isOne();
        assertThat(contar("select count(*) from payments_webhookevento where pedido_id = ?", pedido)).isOne();
        // CASCADE do blog: so as linhas do alvo
        assertThat(contar("select count(*) from blog_comentario where customer_id = ?", alvo)).isZero();
        assertThat(contar("select count(*) from blog_reacao where customer_id = ?", alvo)).isZero();
        assertThat(contar("select count(*) from blog_comentario where customer_id = ?", outro)).isOne();
        assertThat(contar("select count(*) from blog_reacao where customer_id = ?", outro)).isOne();
        assertThat(contar("select count(*) from blog_customerbanido where customer_id = ?", alvo)).isZero();
        // SET_NULL do revogado_por: o ban do outro fica, revogado, sem quem revogou
        assertThat(jdbc.queryForMap("select revogado_por_id, revogado_em from blog_customerbanido where id = ?",
                banDoOutro)).satisfies(m -> {
                    assertThat(m.get("revogado_por_id")).isNull();
                    assertThat(m.get("revogado_em")).isNotNull();
                });
        // simplejwt: outstanding SET_NULL (todos revogados antes), blacklisted fica (CASCADE so do outstanding)
        assertThat(contar("select count(*) from token_blacklist_outstandingtoken where user_id is null and jti in "
                + "('jti-1', 'jti-2')")).isEqualTo(2);
        assertThat(contar("select count(*) from token_blacklist_blacklistedtoken b join token_blacklist_outstandingtoken"
                + " t on t.id = b.token_id where t.jti in ('jti-1', 'jti-2')")).isEqualTo(2);
        // M2M e LogEntry.user: CASCADE; a trilha de outro admin fica
        assertThat(contar("select count(*) from users_user_groups where user_id = ?", alvo)).isZero();
        assertThat(contar("select count(*) from users_user_user_permissions where user_id = ?", alvo)).isZero();
        assertThat(contar("select count(*) from auth_group")).isOne();
        assertThat(contar("select count(*) from django_admin_log where user_id = ?", alvo)).isZero();
        assertThat(contar("select count(*) from django_admin_log where user_id = ?", admin)).isOne();
    }

    @Test
    void banidoPorRecusa409() {
        long cliente = usuario("cliente@exemplo.com", false, false);
        long outroAdmin = usuario("outro-admin@exemplo.com", true, false);
        ban(cliente, outroAdmin, null);
        assertThat(codigo(() -> exclusao.excluir(outroAdmin))).isEqualTo("usuario_baniu_alguem");
        assertThat(contar("select count(*) from users_user where id = ?", outroAdmin)).isOne();
        assertThat(contar("select count(*) from blog_customerbanido")).isOne();
    }

    @Test
    void autorDePostRecusa409() {
        long autor = usuario("autor@exemplo.com", true, false);
        jdbc.update("""
                insert into blog_post (created_at, updated_at, id, slug, titulo, conteudo_html, conteudo_tiptap_json,
                    excerpt, meta_title, meta_description, imagem_capa_url, status, historia_ilustrativa, autor_id)
                values (now(), now(), gen_random_uuid(), 's', 'T', '', '{}'::jsonb, '', '', '', '', 'rascunho', false, ?)
                """, autor);
        assertThat(codigo(() -> exclusao.excluir(autor))).isEqualTo("usuario_autor_de_post");
    }

    @Test
    void ultimoSuperusuarioRecusa409() {
        long superusuario = usuario("super@exemplo.com", true, true);
        assertThat(codigo(() -> exclusao.excluir(superusuario))).isEqualTo("ultimo_superusuario");
        // superusuario sem is_clama_admin nao entra no painel: nao conta como "outro"
        usuario("super-sem-painel@exemplo.com", false, true);
        assertThat(codigo(() -> exclusao.excluir(superusuario))).isEqualTo("ultimo_superusuario");
        // com outro superusuario ativo, passa
        usuario("super2@exemplo.com", true, true);
        excluir(superusuario);
    }

    @Test
    void ultimoAdminAtivoRecusa409() {
        jdbc.update("update users_user set is_active = false where id = ?", admin);
        long unico = usuario("unico@exemplo.com", true, false);
        assertThat(codigo(() -> exclusao.excluir(unico))).isEqualTo("ultimo_admin");
    }

    /** A guarda que o PATCH usa ao rebaixar ou desativar (o HTTP so a alcanca em corrida; ver UsuariosAdminTest). */
    @Test
    void exigirOutroAdminDoPatch() {
        long unico = usuario("unico@exemplo.com", true, false);
        jdbc.update("update users_user set is_active = false where id = ?", admin);
        assertThat(codigo(() -> exclusao.exigirOutroAdmin(usuarios.findById(unico).orElseThrow())))
                .isEqualTo("ultimo_admin");
        // alvo que ja nao e admin ativo nao reduz nada: passa
        tx.executeWithoutResult(s -> exclusao.exigirOutroAdmin(usuarios.findById(admin).orElseThrow()));
        // com outro admin ativo, passa
        jdbc.update("update users_user set is_active = true where id = ?", admin);
        tx.executeWithoutResult(s -> exclusao.exigirOutroAdmin(usuarios.findById(unico).orElseThrow()));
    }

    @Test
    void inexistenteDevolveFalse() {
        tx.executeWithoutResult(s -> assertThat(exclusao.excluir(999_999_999L)).isFalse());
    }

    @Test
    void purgarBlogSoLevaComentariosEReacoes() {
        long alvo = usuario("alvo@exemplo.com", false, false);
        UUID pedido = pedido(alvo);
        comentarioEReacao(alvo);
        ban(alvo, admin, null);
        tx.executeWithoutResult(s -> exclusao.purgarBlog(alvo));
        assertThat(contar("select count(*) from blog_comentario")).isZero();
        assertThat(contar("select count(*) from blog_reacao")).isZero();
        assertThat(contar("select count(*) from users_user where id = ?", alvo)).isOne();
        assertThat(jdbc.queryForObject("select user_id from orders_pedido where id = ?", Long.class, pedido))
                .isEqualTo(alvo);
        assertThat(contar("select count(*) from blog_customerbanido where customer_id = ?", alvo)).isOne();
    }
}
