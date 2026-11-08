package me.clama.api.comum.comando;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.clama.api.ClamaApiApplication;
import me.clama.api.catalogo.instituicao.ComandoInstituicoes;
import me.clama.api.comum.agenda.ComandoTarefa;
import me.clama.api.comum.erro.PastoralException;
import me.clama.api.comum.observabilidade.SentryConfig;
import me.clama.api.instituicao.repasse.comando.AuditarRepasses;
import me.clama.api.instituicao.repasse.comando.MarcarRepasseTransferido;
import me.clama.api.instituicao.repasse.comando.RelatorioRepasse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * Modo comando do jar (mapa §9): java -jar clama-api.jar &lt;comando&gt; [args]. Le os argumentos antes do Spring (erro
 * de uso = exit 2 sem tocar no banco), sobe o contexto no perfil comando (sem web, fila, agenda nem Flyway), roda o bean
 * do comando e devolve o exit code: 0 ok, 1 CommandError ou excecao, 2 uso (§9.5).
 */
public final class Comandos {
    private static final Logger LOG = LoggerFactory.getLogger(Comandos.class);
    static final String JAR = "java -jar clama-api.jar";

    /** Os comandos do jar (D5: nomes do Django). Cada task acrescenta os seus. */
    static final Map<String, Definicao> DEFINICOES = porNome(
            new Definicao("instituicoes", ComandoInstituicoes.class, ComandoInstituicoes.OPCOES),
            new Definicao("tarefa", ComandoTarefa.class, ComandoTarefa.OPCOES),
            new Definicao("relatorio_repasse", RelatorioRepasse.class, RelatorioRepasse.OPCOES),
            new Definicao("auditar_repasses", AuditarRepasses.class, AuditarRepasses.OPCOES),
            new Definicao("marcar_repasse_transferido", MarcarRepasseTransferido.class,
                    MarcarRepasseTransferido.OPCOES));

    private Comandos() {
    }

    public record Definicao(String nome, Class<? extends Comando> classe, List<Opcao> opcoes) {
    }

    public enum Tipo { VALOR, OBRIGATORIA, FLAG, POSICIONAL }

    /**
     * nome: "--flag" (VALOR, OBRIGATORIA = required=True do argparse, FLAG) ou o nome do posicional (obrigatorio, na
     * ordem). escolhas vazia = qualquer valor.
     */
    public record Opcao(String nome, Tipo tipo, List<String> escolhas) {
        public static Opcao valor(String nome, String... escolhas) {
            return new Opcao(nome, Tipo.VALOR, List.of(escolhas));
        }

        public static Opcao obrigatoria(String nome) {
            return new Opcao(nome, Tipo.OBRIGATORIA, List.of());
        }

        public static Opcao flag(String nome) {
            return new Opcao(nome, Tipo.FLAG, List.of());
        }

        public static Opcao posicional(String nome) {
            return new Opcao(nome, Tipo.POSICIONAL, List.of());
        }
    }

    static final class ErroDeUso extends Exception {
        ErroDeUso(String mensagem) {
            super(mensagem);
        }
    }

    static Map<String, Definicao> porNome(Definicao... definicoes) {
        Map<String, Definicao> m = new LinkedHashMap<>();
        for (Definicao d : definicoes) {
            if (m.put(d.nome(), d) != null) {
                throw new IllegalStateException("Comando duplicado: '" + d.nome() + "'");
            }
        }
        return m;
    }

    /** O primeiro argumento nao e opcao: e um comando (conhecido ou nao, o web nunca sobe por engano). */
    public static boolean ehComando(String[] args) {
        return args.length > 0 && !args[0].startsWith("-");
    }

    /** O main do modo comando: stdout e stderr do processo em UTF-8 (R2), qualquer System.out vai pro stderr (R1). */
    public static int rodar(String[] args) {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, UTF_8);
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, UTF_8);
        System.setOut(err);
        System.setErr(err);
        return executar(args, out, err, new BufferedReader(new InputStreamReader(System.in, UTF_8)),
                Path.of("").toAbsolutePath(), null);
    }

    /**
     * Travas do modo comando (D6, R3, R6, R7). O railway run injeta toda variavel do servico, e variavel de ambiente,
     * SPRING_APPLICATION_JSON e -D venceriam o application-comando.yml: por isso vao na 1a fonte de propriedades.
     */
    static final Map<String, Object> TRAVAS = Map.of("spring.flyway.enabled", "false",
            "clama.fila.scheduler-ativo", "false", "clama.agenda.ativa", "false",
            "spring.main.web-application-type", "none");

    /**
     * O contexto do modo comando. Sem os args (R5: o Spring faria de --x=y uma propriedade). sobrescritas: so para o
     * ComandoBootTest (o banco do container); acima de tudo menos das {@link #TRAVAS}. A fonte entra quando o
     * ambiente fica pronto, antes do bind de spring.main.*, entao nem o tipo de aplicacao escapa.
     */
    public static ConfigurableApplicationContext abrir(Map<String, Object> sobrescritas) {
        Map<String, Object> fonte = new HashMap<>(sobrescritas);
        fonte.putAll(TRAVAS);
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(ClamaApiApplication.class)
                .profiles("comando")
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .listeners(e -> {
                    if (e instanceof ApplicationEnvironmentPreparedEvent p) {
                        p.getEnvironment().getPropertySources().addFirst(new MapPropertySource("comando-travas", fonte));
                    }
                })
                .run();
        if (ctx instanceof WebServerApplicationContext) { // nunca deveria: as travas acima; se escapar, nao segue
            ctx.close();
            throw new IllegalStateException("Modo comando subiu um servidor web; abortado");
        }
        return ctx;
    }

    /** ctx null: abre o contexto do modo comando e fecha no fim (o jar); senao usa o dado (testes, em processo). */
    public static int executar(String[] args, PrintStream out, PrintStream err, BufferedReader in, Path diretorio,
            ApplicationContext ctx) {
        return executar(DEFINICOES, args, out, err, in, diretorio, ctx);
    }

    static int executar(Map<String, Definicao> definicoes, String[] args, PrintStream out, PrintStream err,
            BufferedReader in, Path diretorio, ApplicationContext ctx) {
        Definicao d = definicoes.get(args[0]);
        if (d == null) {
            err.println("Comando desconhecido: '" + args[0] + "'. Disponíveis: "
                    + String.join(", ", definicoes.keySet()));
            return 2;
        }
        Map<String, String> opcoes;
        try {
            opcoes = ler(d, Arrays.asList(args).subList(1, args.length));
        } catch (ErroDeUso e) {
            err.println(uso(d));
            err.println(d.nome() + ": error: " + e.getMessage());
            return 2;
        }
        ConfigurableApplicationContext aberto = null;
        int exit = 1;
        try {
            if (ctx == null) {
                ctx = aberto = abrir(Map.of());
            }
            // so os nomes das opcoes: valor pode ser PII (e-mail, senha) (§9.9)
            LOG.info("Comando {}: inicio, opcoes {}", d.nome(), opcoes.keySet());
            exit = ctx.getBean(d.classe()).executar(new Chamada(opcoes, out, err, in, diretorio));
        } catch (ErroDeComando e) {
            err.println("CommandError: " + e.getMessage());
        } catch (PastoralException e) {
            err.println("CommandError: " + e.pastoralMessage());
        } catch (Throwable e) { // Error tambem: traceback, Sentry e exit 1
            e.printStackTrace(err);
            SentryConfig.capturar("comando", d.nome(), e);
        } finally {
            LOG.info("Comando {}: fim, exit {}", d.nome(), exit);
            if (aberto != null) {
                aberto.close();
            }
        }
        return exit;
    }

    /**
     * argparse sem abreviacao de prefixo (desvio aceito, §9.2): "--x v" e "--x=v", a ultima vence; flag sem valor;
     * posicionais na ordem, todos obrigatorios. Valor que comeca com "--" e a proxima opcao, nao um valor.
     */
    static Map<String, String> ler(Definicao d, List<String> args) throws ErroDeUso {
        Map<String, String> lidas = new LinkedHashMap<>();
        List<String> sobra = new ArrayList<>();
        Iterator<Opcao> posicionais = d.opcoes().stream().filter(o -> o.tipo() == Tipo.POSICIONAL).iterator();
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (!a.startsWith("--")) {
                if (posicionais.hasNext()) {
                    lidas.put(posicionais.next().nome(), a);
                } else {
                    sobra.add(a);
                }
                continue;
            }
            int igual = a.indexOf('=');
            String nome = igual < 0 ? a : a.substring(0, igual);
            Opcao o = d.opcoes().stream().filter(x -> x.tipo() != Tipo.POSICIONAL && x.nome().equals(nome))
                    .findFirst().orElse(null);
            if (o == null) {
                sobra.add(a);
            } else if (o.tipo() == Tipo.FLAG) {
                if (igual >= 0) {
                    throw new ErroDeUso("argument " + nome + ": ignored explicit argument '" + a.substring(igual + 1)
                            + "'");
                }
                lidas.put(nome, "true");
            } else {
                String v;
                if (igual >= 0) {
                    v = a.substring(igual + 1);
                } else if (i + 1 < args.size() && !args.get(i + 1).startsWith("--")) {
                    v = args.get(++i);
                } else {
                    throw new ErroDeUso("argument " + nome + ": expected one argument");
                }
                if (!o.escolhas().isEmpty() && !o.escolhas().contains(v)) {
                    throw new ErroDeUso("argument " + nome + ": invalid choice: '" + v + "' (choose from "
                            + String.join(", ", o.escolhas()) + ")");
                }
                lidas.put(nome, v);
            }
        }
        List<String> faltam = d.opcoes().stream() // na ordem da definicao, como o argparse
                .filter(o -> (o.tipo() == Tipo.POSICIONAL || o.tipo() == Tipo.OBRIGATORIA) && !lidas.containsKey(o.nome()))
                .map(Opcao::nome).toList();
        if (!faltam.isEmpty()) {
            throw new ErroDeUso("the following arguments are required: " + String.join(", ", faltam));
        }
        if (!sobra.isEmpty()) {
            throw new ErroDeUso("unrecognized arguments: " + String.join(" ", sobra));
        }
        return lidas;
    }

    static String uso(Definicao d) {
        StringBuilder s = new StringBuilder("usage: " + JAR + " " + d.nome());
        for (Opcao o : d.opcoes()) {
            if (o.tipo() == Tipo.FLAG) {
                s.append(" [").append(o.nome()).append("]");
            } else if (o.tipo() != Tipo.POSICIONAL) {
                String v = o.nome() + " " + (o.escolhas().isEmpty()
                        ? o.nome().substring(2).toUpperCase(java.util.Locale.ROOT).replace('-', '_')
                        : "{" + String.join(",", o.escolhas()) + "}");
                s.append(" ").append(o.tipo() == Tipo.OBRIGATORIA ? v : "[" + v + "]");
            }
        }
        d.opcoes().stream().filter(o -> o.tipo() == Tipo.POSICIONAL).forEach(o -> s.append(" ").append(o.nome()));
        return s.toString();
    }
}
