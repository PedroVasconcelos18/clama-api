package me.clama.api.identidade.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.clama.api.comum.api.CamposDrf;
import me.clama.api.comum.cripto.HashIdentificador;
import me.clama.api.comum.email.EnviadorDeEmail;
import me.clama.api.comum.email.ValidadorDeEmail;
import me.clama.api.comum.email.modelo.ModelosDeEmail;
import me.clama.api.comum.ratelimit.LimiteDeTaxa;
import me.clama.api.comum.texto.TextoPython;
import me.clama.api.conta.GeradorDeSenhaTemporaria;
import me.clama.api.identidade.Usuario;
import me.clama.api.identidade.UsuarioRepository;
import me.clama.api.identidade.senha.SenhaDjango;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * ForgotPasswordView: responde sempre 200 generico (anti-enumeracao). Conta ativa e nao admin ganha uma senha
 * temporaria, gravada na hora (sem token de reset), e o e-mail sai sincrono depois do commit. Alem do Django: se o
 * e-mail pedido nao casa, tenta a forma canonica do Gmail (contas criadas por doacao guardam essa).
 */
@RestController
class EsqueciSenhaController {
    private static final Logger LOG = LoggerFactory.getLogger(EsqueciSenhaController.class);
    static final String GENERICA = "Se este e-mail estiver cadastrado, você receberá uma senha temporária em "
            + "instantes. Verifique também a caixa de spam.";

    private final UsuarioRepository usuarios;
    private final SenhaDjango senhas;
    private final ModelosDeEmail modelos;
    private final EnviadorDeEmail enviador;
    private final TransactionTemplate tx;

    EsqueciSenhaController(UsuarioRepository usuarios, SenhaDjango senhas, ModelosDeEmail modelos,
            EnviadorDeEmail enviador, TransactionTemplate tx) {
        this.usuarios = usuarios;
        this.senhas = senhas;
        this.modelos = modelos;
        this.enviador = enviador;
        this.tx = tx;
    }

    private record Troca(String email, String primeiroNome, String senha) {}

    @PostMapping("/api/customer/auth/forgot-password/")
    @LimiteDeTaxa(escopo = "customer_forgot_password", limite = 3, janelaSegundos = 3600)
    Map<String, String> esqueci(@RequestBody(required = false) JsonNode corpo) {
        CamposDrf campos = new CamposDrf(corpo);
        String email = campos.texto("email", true, "Este campo pode não estar em branco.", s -> List.of(),
                s -> ValidadorDeEmail.valido(s) ? List.of() : List.of("Insira um endereço de email válido."));
        campos.validar();
        Troca troca = null;
        try {
            troca = tx.execute(st -> trocarSenha(email));
        } catch (RuntimeException e) { // qualquer erro vira o mesmo 200: nada de oraculo por status
            LOG.error("Erro inesperado no forgot-password", e);
        }
        if (troca != null) {
            try {
                enviador.enviar(modelos.recuperacaoSenha(troca.email(), troca.primeiroNome(), troca.senha()));
            } catch (RuntimeException e) {
                LOG.error("Falha ao enviar e-mail de recuperação de senha", e);
            }
        }
        Map<String, String> resposta = new LinkedHashMap<>();
        resposta.put("detail", GENERICA);
        return resposta;
    }

    private Troca trocarSenha(String email) {
        List<Long> achados = usuarios.idsPorEmailIgnorandoCaixa(email);
        if (achados.isEmpty()) {
            achados = usuarios.idsPorEmailIgnorandoCaixa(HashIdentificador.normalizarEmail(email));
        }
        if (achados.size() != 1) { // nenhum, ou ambiguo (o get() do Django estoura e cai no generico)
            LOG.info("Forgot-password sem conta unica para o e-mail (dominio {})",
                    email.contains("@") ? email.substring(email.lastIndexOf('@') + 1) : "?");
            return null;
        }
        Usuario u = usuarios.travar(achados.get(0)).orElse(null);
        if (u == null || u.isClamaAdmin() || !u.isActive()) {
            LOG.info("Forgot-password ignorado (admin/inativo)");
            return null;
        }
        String senha = GeradorDeSenhaTemporaria.gerar();
        u.setPassword(senhas.encode(senha));
        u.setForceChangePassword(true);
        List<String> partes = TextoPython.split(u.getNomeCompleto());
        return new Troca(u.getEmail(), partes.isEmpty() ? "Amada" : partes.get(0), senha);
    }
}
