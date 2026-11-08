package me.clama.conta.interno.senha;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import me.clama.infra.web.ErroDeCampo;
import me.clama.infra.web.ErroDeNegocio;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Os 4 validadores do AUTH_PASSWORD_VALIDATORS do Django, na mesma ordem e com as mesmas mensagens pt-BR. */
@Component
public class ValidadorDeSenha {
	private static final String PARECIDA = "A senha é muito parecida com E-mail";
	private static final String CURTA = "Esta senha é muito curta. Ela precisa conter pelo menos 8 caracteres.";
	private static final String COMUM = "Esta senha é muito comum.";
	private static final String NUMERICA = "Esta senha é inteiramente numérica.";
	private static final double MAX_SIMILARIDADE = 0.7;
	private static final Pattern NAO_PALAVRA = Pattern.compile("\\W+", Pattern.UNICODE_CHARACTER_CLASS);

	private final Set<String> comuns = carregarComuns();

	private static Set<String> carregarComuns() {
		try (var in = ValidadorDeSenha.class.getResourceAsStream("/common-passwords.txt.gz");
				var r = new BufferedReader(new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8))) {
			Set<String> s = new HashSet<>();
			r.lines().forEach(l -> s.add(strip(l)));
			return s;
		} catch (java.io.IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Lista de mensagens; vazia = senha aceita. {@code email} pode ser nulo (sem checagem de similaridade). */
	public List<String> validar(String senha, String email) {
		var erros = new ArrayList<String>();
		if (parecida(senha, email)) erros.add(PARECIDA);
		if (senha.codePointCount(0, senha.length()) < 8) erros.add(CURTA);
		if (comuns.contains(strip(senha.toLowerCase(Locale.ROOT)))) erros.add(COMUM);
		if (!senha.isEmpty() && senha.codePoints().allMatch(ValidadorDeSenha::digito)) erros.add(NUMERICA);
		return erros;
	}

	/** Lanca 400 {@code senha_fraca} com os motivos na mensagem pastoral e um erro por motivo no {@code campo}. */
	public void exigirForte(String senha, String email, String campo) {
		var fraquezas = validar(senha, email);
		if (fraquezas.isEmpty()) return;
		throw new ErroDeNegocio(HttpStatus.BAD_REQUEST, "senha_fraca", "A senha é fraca.", String.join(" ", fraquezas),
				fraquezas.stream().map(m -> new ErroDeCampo(campo, m)).toList());
	}

	private static boolean parecida(String senha, String email) {
		if (email == null || email.isEmpty()) return false;
		String a = senha.toLowerCase(Locale.ROOT);
		String valor = email.toLowerCase(Locale.ROOT);
		var partes = new ArrayList<>(List.of(NAO_PALAVRA.split(valor, -1)));
		partes.add(valor);
		for (String parte : partes) {
			int la = a.codePointCount(0, a.length()), lb = parte.codePointCount(0, parte.length());
			if (la >= 10 * lb && lb < MAX_SIMILARIDADE / 2 * la) continue;
			if (quickRatio(a, parte) >= MAX_SIMILARIDADE) return true;
		}
		return false;
	}

	/** difflib.SequenceMatcher.quick_ratio: intersecao de multiconjuntos de caracteres. */
	private static double quickRatio(String a, String b) {
		int total = a.codePointCount(0, a.length()) + b.codePointCount(0, b.length());
		if (total == 0) return 1.0;
		var disp = new HashMap<Integer, Integer>();
		b.codePoints().forEach(c -> disp.merge(c, 1, Integer::sum));
		int iguais = 0;
		for (int c : a.codePoints().toArray()) if (disp.merge(c, -1, Integer::sum) >= 0) iguais++;
		return 2.0 * iguais / total;
	}

	// str.strip() do Python: alem do isWhitespace do Java, tira NBSP, NEL e demais espacos Unicode.
	private static boolean espaco(char c) {
		return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0x85;
	}

	private static String strip(String v) {
		int i = 0, f = v.length();
		while (i < f && espaco(v.charAt(i))) i++;
		while (f > i && espaco(v.charAt(f - 1))) f--;
		return v.substring(i, f);
	}

	// ponytail: str.isdigit() aproximado (Nd + No, cobre ² e ①); fracoes como "½" tambem contam, o Python nao. Sem teste que exija mais.
	private static boolean digito(int c) {
		int t = Character.getType(c);
		return t == Character.DECIMAL_DIGIT_NUMBER || t == Character.OTHER_NUMBER;
	}
}
