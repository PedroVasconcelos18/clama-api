package me.clama.api.comum.email.modelo;

import java.util.List;
import java.util.Map;
import me.clama.api.comum.email.Email;
import me.clama.api.comum.texto.TextoPython;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Os 5 e-mails do Django (clama/notifications/services/email_sender.py). Aqui so monta; quem envia e o EnviadorDeEmail. */
@Component
public class ModelosDeEmail {
    private static final String CLAMA_URL = "https://clama.me";

    private final RenderizadorDeTemplate renderizador = new RenderizadorDeTemplate();
    private final String loginUrl;

    @Autowired
    public ModelosDeEmail(@Value("${FRONTEND_BASE_URL:}") String frontendBaseUrl,
            @Value("${FRONTEND_URL:}") String frontendUrl) {
        String base = !frontendBaseUrl.isEmpty() ? frontendBaseUrl
                : !frontendUrl.isEmpty() ? frontendUrl : "http://localhost:5173";
        this.loginUrl = base.replaceAll("/+$", "") + "/login";
    }

    public Email oracao(String para, String nome, String oracao) {
        String primeiro = primeiroNome(nome);
        return montar(para, "Sua oração, " + primeiro + " 🙏", "email/oracao", Map.of(
                "primeiro_nome", primeiro, "oracao", oracao, "whatsapp_share_url", whatsappShareUrl(oracao)));
    }

    public Email oracaoFreemium(String para, String nome, String oracao, String loginEmail, String senhaTemporaria) {
        String primeiro = primeiroNome(nome);
        return montar(para, "Sua oração chegou, " + primeiro, "email/oracao_freemium", Map.of(
                "primeiro_nome", primeiro, "oracao", oracao, "login_email", loginEmail,
                "senha_temporaria", senhaTemporaria, "login_url", loginUrl));
    }

    public Email confirmacaoFreemium(String para, String nome, String linkConfirmacao) {
        return montar(para, "Confirme sua oração — clama", "email/confirmacao_freemium", Map.of(
                "primeiro_nome", primeiroNome(nome), "link_confirmacao", linkConfirmacao, "expira_em_horas", "24"));
    }

    public Email recuperacaoSenha(String para, String primeiroNome, String senhaTemporaria) {
        return montar(para, "Sua nova senha temporária — Clama", "email/recuperacao_senha", Map.of(
                "primeiro_nome", primeiroNome.isEmpty() ? "Amada" : primeiroNome, "login_email", para,
                "senha_temporaria", senhaTemporaria, "login_url", loginUrl));
    }

    public Email boasVindasDoador(String para, String nomeCompleto, String senhaTemporaria, String loginUrl) {
        return montar(para, "Bem-vinda ao Clama — seus dados de acesso", "email/boas_vindas_doador", Map.of(
                "primeiro_nome", primeiroNome(nomeCompleto), "login_email", para,
                "senha_temporaria", senhaTemporaria, "login_url", loginUrl));
    }

    private Email montar(String para, String assunto, String template, Map<String, String> ctx) {
        return new Email(para, assunto, renderizador.renderizar(template + ".txt", ctx),
                renderizador.renderizar(template + ".html", ctx));
    }

    /** nome.split()[0] se houver nome; "Amada" se vazio. Nome so de espacos: o Django estoura (IndexError); aqui tambem cai em "Amada". */
    private static String primeiroNome(String nome) {
        List<String> partes = TextoPython.split(nome);
        return partes.isEmpty() ? "Amada" : partes.get(0);
    }

    private static String whatsappShareUrl(String oracao) {
        String mensagem = "Recebi essa oração através do Clama:\n\n\"" + oracao + "\"\n\nFaça também seu pedido: " + CLAMA_URL;
        return "https://wa.me/?text=" + QuotePython.quote(mensagem);
    }
}
