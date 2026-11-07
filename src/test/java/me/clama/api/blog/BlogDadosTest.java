package me.clama.api.blog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import me.clama.api.BancoDeTeste;
import me.clama.api.RelogioDeTeste;
import me.clama.api.comum.cripto.CriptoConfig;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** As quatro tabelas do blog passam no ddl-auto=validate, datas vem do Clock e o IP e cifrado. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({BancoDeTeste.class, CriptoConfig.class, RelogioDeTeste.class})
@ActiveProfiles("test")
class BlogDadosTest {
    static final Instant T0 = Instant.parse("2026-03-04T05:06:07.123456Z");
    @Autowired PostEspelhoRepository espelhos;
    @Autowired ComentarioRepository comentarios;
    @Autowired ReacaoRepository reacoes;
    @Autowired CustomerBanidoRepository banidos;
    @Autowired UsuarioRepository usuarios;
    @Autowired RelogioDeTeste.Fixavel relogio;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void antes() {
        BancoDeTeste.exigirContainer(jdbc);
        relogio.fixar(T0);
    }

    private PostEspelho espelho(int wp, String slug) {
        PostEspelho e = new PostEspelho();
        e.setWpPostId(wp);
        e.setSlug(slug);
        e.setTitulo("Titulo " + slug);
        e.setStatus(PostEspelho.PUBLICADO);
        return espelhos.save(e);
    }

    private Usuario usuario(String email) {
        Usuario u = new Usuario();
        u.setEmail(email);
        u.setNomeCompleto("Maria Silva");
        return usuarios.save(u);
    }

    @Test
    void espelhoGravaDatasDoRelogioEPostLegadoFicaNulo() {
        PostEspelho e = espelho(1001, "oracao");
        assertThat(e.getCreatedAt()).isEqualTo(OffsetDateTime.ofInstant(T0, ZoneOffset.UTC));
        assertThat(e.getUpdatedAt()).isEqualTo(e.getCreatedAt());
        assertThat(e.aceitaInteracao()).isTrue();
        assertThat(e.getPostLegadoId()).isNull();
        assertThat(espelhos.findByWpPostId(1001)).get().extracting(PostEspelho::getSlug).isEqualTo("oracao");
        assertThat(espelhos.findBySlug("oracao")).hasSize(1);
        relogio.fixar(T0.plusSeconds(60));
        e.setTitulo("Novo");
        espelhos.save(e);
        assertThat(espelhos.findBySlug("oracao")).singleElement().satisfies(x -> assertThat(x.getUpdatedAt())
                .isEqualTo(OffsetDateTime.ofInstant(T0.plusSeconds(60), ZoneOffset.UTC)));
        assertThat(e.getCreatedAt()).isEqualTo(OffsetDateTime.ofInstant(T0, ZoneOffset.UTC));
    }

    @Test
    void espelhoNaoExpoeDelete() {
        assertThat(PostEspelhoRepository.class.getMethods()).extracting(m -> m.getName())
                .noneMatch(n -> n.startsWith("delete") || n.equals("remove"));
    }

    @Test
    void comentarioCifraIpIncluindoVazioERecalculaSuspeito() {
        PostEspelho e = espelho(1002, "graca");
        Usuario u = usuario("c1@exemplo.com");
        Comentario c = new Comentario();
        c.setCustomer(u);
        c.setPostEspelho(e);
        c.setConteudo("Que merda de texto");
        c.setIpAddress("203.0.113.9");
        comentarios.saveAndFlush(c);
        assertThat(c.isSuspeito()).isTrue();
        assertThat(c.getCreatedAt()).isEqualTo(OffsetDateTime.ofInstant(T0, ZoneOffset.UTC));
        String cru = jdbc.queryForObject("select ip_address from blog_comentario where id = ?", String.class,
                c.getId());
        assertThat(cru).isNotEqualTo("203.0.113.9").startsWith("gAAAA");

        c.setConteudo("Texto limpo agora");
        relogio.fixar(T0.plusSeconds(5));
        comentarios.saveAndFlush(c);
        assertThat(c.isSuspeito()).isFalse();
        assertThat(jdbc.queryForObject("select is_suspeito from blog_comentario where id = ?", Boolean.class,
                c.getId())).isFalse();
        assertThat(c.getUpdatedAt()).isEqualTo(OffsetDateTime.ofInstant(T0.plusSeconds(5), ZoneOffset.UTC));

        Comentario vazio = new Comentario();
        vazio.setCustomer(u);
        vazio.setPostEspelho(e);
        vazio.setConteudo("ok");
        comentarios.saveAndFlush(vazio);
        assertThat(jdbc.queryForObject("select ip_address from blog_comentario where id = ?", String.class,
                vazio.getId())).isNotEmpty().startsWith("gAAAA");
        comentarios.flush();
        assertThat(comentarios.findById(vazio.getId())).get().extracting(Comentario::getIpAddress).isEqualTo("");
    }

    @Test
    void reacaoELikePorEspelhoComUnique() {
        PostEspelho e = espelho(1003, "fe");
        Usuario u = usuario("c2@exemplo.com");
        Reacao r = new Reacao();
        r.setCustomer(u);
        r.setPostEspelho(e);
        reacoes.saveAndFlush(r);
        assertThat(r.getTipo()).isEqualTo("like");
        assertThat(jdbc.queryForObject("select count(*) from blog_reacao where post_espelho_id = ?", Integer.class,
                e.getId())).isEqualTo(1);
    }

    @Test
    void banimentoUsaORelogioNoBanidoEmENaRevogacao() {
        Usuario admin = usuario("a@exemplo.com");
        Usuario u = usuario("c3@exemplo.com");
        CustomerBanido b = new CustomerBanido();
        b.setCustomer(u);
        b.setBanidoPor(admin);
        b.setMotivo("spam");
        banidos.saveAndFlush(b);
        OffsetDateTime t0 = OffsetDateTime.ofInstant(T0, ZoneOffset.UTC);
        assertThat(b.getBanidoEm()).isEqualTo(t0);
        assertThat(b.getRevogadoEm()).isNull();
        relogio.fixar(T0.plusSeconds(3600));
        b.revogar(admin, OffsetDateTime.now(relogio));
        banidos.saveAndFlush(b);
        assertThat(jdbc.queryForObject("select revogado_em from blog_customerbanido where id = ?",
                OffsetDateTime.class, b.getId()).toInstant()).isEqualTo(T0.plusSeconds(3600));
        assertThat(b.getBanidoEm()).isEqualTo(t0);
    }
}
