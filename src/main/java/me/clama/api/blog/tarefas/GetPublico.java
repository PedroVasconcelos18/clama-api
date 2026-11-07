package me.clama.api.blog.tarefas;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;

/**
 * O requests.get dos monitores: sem retry da aplicacao, sem autenticacao. Falha de rede vira o nome da classe da
 * excecao do requests ({@link #nomeDoErro}). O corpo e lido ate {@link #LIMITE} caracteres.
 *
 * <p>ponytail: o timeout do JDK vale ate os headers; o do requests e por leitura de socket, entao um corpo que pinga
 * devagar prende a thread (o requests cortaria em 10 s sem bytes). Charset do Content-Type, senao UTF-8 (o requests
 * usaria ISO-8859-1 para text/* sem charset ou o chardet). Os monitores so procuram ASCII no corpo.
 */
final class GetPublico {
    /** resposta.text[:200000] */
    static final int LIMITE = 200_000;
    private static final Pattern CHARSET = Pattern.compile("charset=\"?([^\";\\s]+)", Pattern.CASE_INSENSITIVE);

    record Resposta(int status, String texto, HttpHeaders headers) {
        /** headers.get(nome, "") do requests: varios valores juntos por ", ". */
        String header(String nome) {
            return String.join(", ", headers.allValues(nome));
        }
    }

    private GetPublico() {}

    static HttpClient cliente(Duration timeout, HttpClient.Redirect redirect) {
        return HttpClient.newBuilder().connectTimeout(timeout).followRedirects(redirect).build();
    }

    /** GET; lerCorpo=false descarta o corpo (verificar-urls so olha o status). */
    static Resposta get(HttpClient http, String url, Duration timeout, boolean lerCorpo) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build();
        try {
            HttpResponse<InputStream> r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream corpo = r.body()) {
                return new Resposta(r.statusCode(), lerCorpo ? ler(corpo, charset(r.headers())) : "", r.headers());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("monitor interrompido", e);
        }
    }

    private static String ler(InputStream corpo, Charset charset) throws IOException {
        char[] buf = new char[LIMITE];
        int n = 0;
        try (Reader in = new InputStreamReader(corpo, charset)) {
            for (int k; n < LIMITE && (k = in.read(buf, n, LIMITE - n)) != -1; ) n += k;
        }
        return new String(buf, 0, n);
    }

    private static Charset charset(HttpHeaders h) {
        Matcher m = CHARSET.matcher(h.firstValue("Content-Type").orElse(""));
        try {
            return m.find() ? Charset.forName(m.group(1)) : StandardCharsets.UTF_8;
        } catch (IllegalArgumentException e) {
            return StandardCharsets.UTF_8;
        }
    }

    /** type(exc).__name__ da RequestException que o requests levantaria. */
    static String nomeDoErro(IOException e) {
        if (e instanceof HttpConnectTimeoutException) return "ConnectTimeout";
        if (e instanceof HttpTimeoutException) return "ReadTimeout";
        if (e instanceof SSLException) return "SSLError";
        return "ConnectionError";
    }
}
