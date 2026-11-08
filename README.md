# clama-api

Backend Java/Spring Boot (migracao do clama-backend Django). Pacote `me.clama.api`.

- Java 27: `export JAVA_HOME=/opt/homebrew/opt/openjdk` (sem fallback para 25 ate agora; Boot 4.1.2 e Testcontainers funcionam).
- Postgres de producao: **18.6** (`show server_version`). Os testes usam `postgres:18` com `src/test/resources/db/schema-prod.sql` (pg_dump --schema-only de producao).

## Rodar os testes

Os testes precisam de Docker (Testcontainers). Com o colima:

```bash
colima start
export JAVA_HOME=/opt/homebrew/opt/openjdk
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
./mvnw verify
```

**Nunca exporte `DATABASE_URL` no shell em que roda os testes.** O replay do contrato faz `TRUNCATE users_user ... CASCADE`, o que apaga pedidos, blog e tokens. Hoje ha duas protecoes. O surefire ativa o perfil `test`, e nele o DataSource do `DATABASE_URL` nao existe (`@Profile("!test")` em `DatabaseUrlConfig`). Alem disso, toda limpeza chama `BancoDeTeste.exigirContainer`, que recusa qualquer banco que nao seja `localhost/clama_testcontainers`. Mesmo assim, nao conte com elas: um `DATABASE_URL` de producao exportado no shell e um acidente esperando a vez.

## Configuracao de producao (web)

- `PORT` (default 8080). Shutdown graceful de 15 s; `forward-headers-strategy: none`; sem `/actuator/**`.
- `DJANGO_ALLOWED_HOSTS` (lista separada por virgula, `.dominio` e curinga; default `clama.me,www.clama.me,api.clama.me`): Host fora da lista leva 400. `healthcheck.railway.app` passa sempre. **Rodando local, exporte `DJANGO_ALLOWED_HOSTS=localhost,127.0.0.1`** (ou `clama.hosts.validar=false`).
- HSTS (`max-age=60; includeSubDomains; preload`) sai quando o 1o valor do `X-Forwarded-Proto` e `https`; COOP `same-origin` sempre.
- Webhook do Mercado Pago recusa corpo acima de 2,5 MB com 400, antes do HMAC.

## Modo comando e a rotina mensal de repasse

O jar tambem roda comandos pontuais (equivalentes aos `manage.py` do Django): `java -jar target/clama-api.jar <comando> [flags]`. Se o 1o argumento e um comando conhecido (`instituicoes`, `tarefa`, `relatorio_repasse`, `auditar_repasses`, `marcar_repasse_transferido`, `adotar_pedidos_orfaos`, `create_clama_admin`, `purgar_dados_blog_customer`, `lgpd_export`, `lgpd_delete`), o `main` sobe um contexto sem web, sem poller da fila, sem agenda e com o Flyway desligado, executa e sai com o exit code do comando. O stdout leva so o resultado (o log vai para o stderr), sempre em UTF-8.

Notas dos comandos de conta:
- `create_clama_admin --password <senha>` deixa a senha no historico do shell e no argv (`ps`); prefira omitir `--password` e digitar no prompt (duas vezes, sem eco).
- Sem terminal (pipe, redirecionamento), a senha vem de UMA linha do stdin, e ali nao ha como esconder o eco.
- `--superusuario` e o resgate para quando se perde o unico superusuario: cria o admin tambem com `is_superuser`. A tela do painel nunca cria superusuario.
- `--autor <id>` grava a criacao no `django_admin_log` em nome desse usuario (a tabela exige autor); sem ele so sai o log estruturado.

Notas dos comandos LGPD (`--email` e comparado na forma canonica: caixa, Gmail sem pontos e `+tag`):
- `lgpd_export --email <e> [--output-dir lgpd_exports]` grava `lgpd_<AAAAmmdd_HHMMSS>.json` (arquivo 0600, diretorio criado com 0700, ja no `.gitignore`; sem o e-mail no nome, ao contrario do Django, para nao furar o `m***@`) com pedidos, contas, blog e se a pessoa consta na blacklist do freemium. Apague o arquivo depois de enviar ao titular.
- `lgpd_delete --email <e>` so lista (dry-run). Com `--confirm`, pede `DELETAR` no stdin e, numa transacao, exclui as contas, apaga os pedidos e anonimiza os que tem repasse (registro contabil). A blacklist do freemium fica. Guarda que falha (ultimo admin, conta que baniu alguem) = exit 1 e nada muda. `--autor <id>` audita.

Rodar um comando localmente (a mesma `bash`/JDK 27 dos testes; o `java` do PATH do Mac pode ser o 25):

```bash
./mvnw -q package -DskipTests        # gera target/clama-api.jar (finalName fixo)
export DATABASE_URL=postgres://usuario:senha@localhost:5432/banco   # NUNCA o de producao fora da virada
export FIELD_ENCRYPTION_KEY=... FREEMIUM_HASH_SECRET=... DJANGO_SECRET_KEY=... \
       MERCADOPAGO_WEBHOOK_SECRET=... WORDPRESS_WEBHOOK_SECRET=...
/opt/homebrew/opt/openjdk@27/bin/java -jar target/clama-api.jar instituicoes
```

As variaveis obrigatorias sao as do boot normal: `DATABASE_URL`, `FIELD_ENCRYPTION_KEY` (decifrar nome e e-mail do doador), `FREEMIUM_HASH_SECRET`, `DJANGO_SECRET_KEY`, `MERCADOPAGO_WEBHOOK_SECRET` e `WORDPRESS_WEBHOOK_SECRET`. Com `railway run --service <servico>` elas vem do servico.

`scripts/relatorio-repasse-mensal.sh` e a versao versionada do script do dia 28 (o script em uso no Mac e o launchd nao dependem dele ate a virada). `MOTOR=django` (padrao) faz o que o script atual faz; `MOTOR=java` chama `$JAVA -jar $JAR <comando>` dentro de `railway run --service $SERVICO --` e conta as instituicoes pelo comando `instituicoes` (`wc -l`) no lugar do `manage.py shell -c`. Variaveis: `MOTOR` e `SERVICO` (padrao `web`) sem prefixo, porque o plano as nomeia assim; os overrides levam o prefixo `CLAMA_`: `CLAMA_JAVA` (padrao `/opt/homebrew/opt/openjdk@27/bin/java`, o launchd nao tem PATH), `CLAMA_JAR` (padrao `$CLAMA_API/target/clama-api.jar`), `CLAMA_API`, `CLAMA_BACKEND`, `CLAMA_RAILWAY`, `CLAMA_RELATORIOS`; ganchos de teste: `CLAMA_HOJE`, `CLAMA_MES`, `CLAMA_DESDE`+`CLAMA_ATE`, `TESTE_EXEC_PREFIX` (substitui o `railway run`; vazio = chama o jar direto). No modo java, se o jar nao existe o script nao roda nada: registra "Jar do clama-api nao encontrado", notifica isso (nao "CRITICO") e sai com 1. O jar tem que ser do mesmo commit do deploy. Teste: `RelatorioMensalIT` (roda no `./mvnw verify`).

**No Railway o start vem do `CMD` do `Dockerfile` (`java -jar /app/clama-api.jar`, sem argumento posicional; ver "Deploy no Railway").** Um argumento que case com um comando faria o servidor virar um processo one-shot e sair.

## Configuracao

- `DATABASE_URL` (formato Railway) vira o DataSource via `DatabaseUrlConfig`, fora do perfil `test`. Sem ela vale a config padrao do Spring.
- `DJANGO_SECRET_KEY`, `FIELD_ENCRYPTION_KEY`, `FREEMIUM_HASH_SECRET` e `MERCADOPAGO_WEBHOOK_SECRET` (esta so fora do perfil `test`) sao obrigatorias e nao tem default. Se faltar alguma, ou se vier em branco, a aplicacao nao sobe. Os valores de teste ficam so em `src/test/resources/application-test.yml`.
- Opcionais, mas com efeito se faltarem:
  - `ANTHROPIC_API_KEY`: a aplicacao sobe, mas loga ERROR e manda um evento ao Sentry no boot; toda geracao de oracao falha (fora do perfil `test` nao ha oracao mock).
  - `ADMIN_ALERT_EMAIL`: o alerta de pedido em erro vai para `contato@clama.me`.
  - `FRONTEND_URL` (ou `FRONTEND_BASE_URL`): o link de login dos e-mails aponta para `http://localhost:5173/login`.
  - `FREEMIUM_TEMP_PWD_KEY`: gera-se uma chave aleatoria por processo; a senha temporaria guardada para o e-mail de boas-vindas nao sobrevive a um restart, e o job de boas-vindas entao nao tem o que enviar (WARN e Sentry se o doador nunca entrou).

## Transacao por requisicao

O Django roda com `ATOMIC_REQUESTS=True`: a view inteira e uma transacao. Aqui nao ha transacao implicita (`open-in-view: false`, controllers sem `@Transactional`). O padrao e este:

1. O controller valida a entrada fora da transacao (`CamposDrf`). Assim, um 400 de validacao nao abre transacao.
2. O trabalho de banco roda numa transacao so: um `TransactionTemplate` no controller (`tx.execute(...)` ou `tx.executeWithoutResult(...)`) quando junta varios passos, como autenticar (que pode regravar o hash da senha) e emitir tokens, ou travar a linha com `select ... for update` na troca de senha. Quando e um passo so, vale o `@Transactional` do servico (`ServicoDeTokens.rotacionar` e `revogarDoUsuario`, no refresh e no logout).
3. Os cookies (`CookiesDeAuth.gravar` e `apagar`) so sao escritos depois que a transacao retorna (o template ou o metodo do servico), ou seja, depois do commit.

O motivo e que um cookie ja escrito na resposta nao volta atras. Se o commit falhar, por exemplo num conflito na rotacao do refresh, o cliente nao pode ficar com um par de tokens que o banco nao registrou, nem perder a sessao por um logout que nao aconteceu. Uma excecao dentro do template faz rollback e vira resposta pelo `PastoralExceptionHandler`, sem cookie nenhum.

## Flyway e fila de jobs (producao)

O Flyway agora roda no boot, so com migrations **aditivas** (tabelas novas, nunca `ALTER`/`DROP` em tabela do Django) ate o cutover estar estavel. Na primeira subida contra o banco do Django ele faz baseline na versao 0 e cria `flyway_schema_history` e `scheduled_tasks` (a fila do db-scheduler, que substitui Celery+Redis). O usuario do banco precisa de permissao `CREATE`. O scheduler so inicia depois das migrations.

O Django ignora essas tabelas, entao voltar para ele e seguro. Mas jobs pendentes em `scheduled_tasks` ficam invisiveis para o Django: no rollback eles nao rodam.

## Regras para jobs (Etapa 4+)

- **Payload so leva IDs.** Nada de PII, senha ou texto de oracao: o payload fica em texto puro em `scheduled_tasks.task_data`. O job busca o resto no banco.
- Backoff: `esperaEntreTentativas()` e a 1a espera, `fatorDeBackoff()` multiplica a cada falha. O `countdown = 30 * 2**retries` do Celery e `30 s` com fator `2.0` (30, 60, 120 s). `executar(payload, retentativa)` recebe o `self.request.retries` (0 na 1a execucao). `esgotou(payload, erro)` roda uma vez quando o job desiste, antes do Sentry; `erro` pode ser nulo.

- **Instancia deterministica** (`Fila.enfileirar(tipo, instancia, payload)`): instancia ja existente = no-op, retorna `false`, e isso inclui o job *em execucao* (a linha so some quando ele termina). Para reagendar a mesma instancia depois de esgotar as retentativas, faca isso de dentro de `esgotou` (a linha ja foi removida), numa transacao propria (`TransactionTemplate`); nunca de dentro de `executar`. Enfileiramentos concorrentes da mesma instancia sao serializados por advisory lock (o db-scheduler faz SELECT + INSERT, sem ON CONFLICT).

- **Antecipar** (`Fila.antecipar(tipo, instancia)`, exige transacao): traz para agora uma instancia ja agendada (o `gerar-oracao` de `aguardando_reenvio`, em +300 s); `false` se ela nao existe ou ja esta em execucao. O reschedule zera as falhas consecutivas da instancia.

- **Testes com o scheduler ligado** usam `@SchedulerLigado` (liga `clama.fila.scheduler-ativo` e poe `@DirtiesContext`): um contexto em cache segue fazendo polling e executaria os jobs (gerar/enviar/alerta) enfileirados por outros testes. Esperas dos jobs de oracao em teste: `clama.oracao.gerar-espera`, `clama.oracao.enviar-espera`, `clama.oracao.reagendamento` (defaults 60 s, 30 s, 300 s).

## Regras para jobs que enviam e-mail (Etapa 4+)

A fila e at-least-once: um job pode rodar de novo depois de ja ter enviado (processo morto entre o envio e o fim do job, redeploy, retentativa).

1. Passe uma chave de idempotencia estavel: `modelos.oracao(...).comChave("oracao/" + pedidoId)`. Ela vira o header `Idempotency-Key` da Resend, que nao reenvia a mesma chave por 24 h. O conteudo precisa ser o mesmo nas retentativas (a Resend responde 409 se a chave voltar com outro payload): nao gere senha temporaria nova dentro do job que reenvia.
2. Envie fora de transacao de banco: nao segure conexao durante o HTTP, e um rollback nao desfaz um e-mail.
3. Logo depois do envio, marque o estado "enviado" numa transacao propria e curta.

## Shutdown no redeploy (Etapa 9)

No SIGTERM, em sequencia:
1. o Tomcat para de aceitar conexoes e espera as requisicoes em andamento por ate 15 s (`timeout-per-shutdown-phase`);
2. a fila (db-scheduler, `shutdownMaxWait` 15 s) espera os jobs em execucao 15 s, **interrompe e espera mais 15 s** (o db-scheduler espera duas vezes: "Will wait up to 2xPT15S");
3. a agenda (`ThreadPoolTaskScheduler`, `awaitTermination` 10 s) espera so a tarefa em andamento: tarefa futura nao roda no shutdown.

Tipicamente ~**55 s** (15 + 30 + 10), dentro do draining de **60 s** do servico; acima disso vem o SIGKILL. Nao e teto rigido: antes da espera dos jobs, o `stop()` do db-scheduler para o due-executor e o housekeeper (ate 1 s + 5 s cada, na pratica milissegundos), entao o pior caso teorico e ~67 s, acima dos 60 s do draining so se o housekeeper travar. Uma chamada a Claude bloqueada no socket nao obedece a interrupcao; ela termina pelo timeout de 30 s do cliente, e o job falha e volta pela retentativa (60 s). Um job morto no meio (SIGKILL, heartbeat de 20 s, 6 perdidos) e retomado em cerca de 2 min. Tempos medidos em `scripts/ensaio-deploy.sh`.

## Deploy no Railway (Dockerfile)

O Railway usa o `Dockerfile` da raiz sozinho: build multi-stage (`./mvnw package -DskipTests` no `eclipse-temurin:27-jdk`), runtime `eclipse-temurin:27-jre`, usuario sem root, `/app/media` gravavel (os uploads somem no redeploy, como no Django) e `CMD ["java", "-jar", "/app/clama-api.jar"]` em forma exec, para o SIGTERM chegar na JVM. A imagem nao leva segredo nenhum: tudo vem das variaveis do servico. O `railway.json` (Config as Code) nao vale para servico novo, entao o resto se configura **no painel**:

| Painel | Valor |
|---|---|
| Criacao | **Empty Service**, sem origem. Ligar o repo dispara um deploy na hora, contra o banco de producao com o Django no ar; a origem so se conecta dentro da janela |
| Origem (so na janela) | o repo `clama-api` na branch que o Pedro publicar com o codigo da virada: `migracao-java/integracao`, ou a `main` depois do merge dela (mapa §2.14). Hoje o repo nao tem remote |
| Regiao / replicas | `us-west2`, 1 replica |
| Limite | 1,5 GB de memoria, 2 vCPU |
| Start Command | **vazio** (um start command do painel roda sob shell e pode engolir o SIGTERM) |
| Healthcheck Path | `/api/health/` (chega com `Host: healthcheck.railway.app`, que o filtro de Host sempre aceita); o timeout default de 300 s cobre JVM + Flyway |
| Restart | `ON_FAILURE` |
| Variaveis de deploy | `RAILWAY_DEPLOYMENT_DRAINING_SECONDS=60`, `RAILWAY_DEPLOYMENT_OVERLAP_SECONDS=0` |
| Dominio | nenhum ate a janela; o `api.clama.me` aponta para a porta 8080 |

Variaveis do servico (nomes do mapa da Etapa 9, §0.4; os valores vem do `web` do Django):
- `PORT=8080`, explicito.
- `JAVA_TOOL_OPTIONS`: a imagem ja traz `-Xmx640m -XX:MaxMetaspaceSize=256m -Xss512k -XX:+ExitOnOutOfMemoryError`; uma variavel no servico sobrescreve sem rebuild. O ensaio local mediu cerca de 540 MiB de RSS depois do boot e 550 a 610 MiB depois de carga leve, com `-m 1536m`.
- Obrigatorias: `DATABASE_URL`, `FIELD_ENCRYPTION_KEY`, `FREEMIUM_HASH_SECRET`, `DJANGO_SECRET_KEY`, `MERCADOPAGO_WEBHOOK_SECRET`, `WORDPRESS_WEBHOOK_SECRET`.
- Opcionais com efeito: `ANTHROPIC_API_KEY`, `FREEMIUM_TEMP_PWD_KEY`, `MERCADOPAGO_ACCESS_TOKEN`, `RESEND_API_KEY`, `TURNSTILE_SECRET_KEY`, `BACKEND_PUBLIC_URL` (tem de ser `https://api.clama.me`), `FRONTEND_URL`, `DJANGO_ALLOWED_HOSTS`, `DJANGO_CORS_ALLOWED_ORIGINS`, `WORDPRESS_API_URL`, `WORDPRESS_API_USER`, `WORDPRESS_API_APP_PASSWORD`, `SENTRY_DSN`, `SENTRY_ENVIRONMENT`.
- Nao copiar: `CELERY_*` (inclusive `CELERY_FLOWER_USER` e `CELERY_FLOWER_PASSWORD`), `REDIS_URL`, `DJANGO_SETTINGS_MODULE`, `DJANGO_ADMIN_URL`, `DJANGO_DEBUG`, `USE_DOCKER`, `POSTGRES_*`, `DEFAULT_FROM_EMAIL` (ninguem le).
- Nunca definir: `SPRING_PROFILES_ACTIVE=test`, `SPRING_JPA_HIBERNATE_DDL_AUTO`, `AUTH_COOKIE_SECURE=false`.

O `DATABASE_URL` com host que nao e `localhost` ganha `sslmode=require`.

Ensaio local da imagem (so docker no colima; Postgres 18 com o `schema-prod.sql`, Claude de mentira, segredos gerados na hora; nunca toca o Railway): `bash scripts/ensaio-deploy.sh`. Ele faz o build, sobe tudo, confere Flyway, health, PID 1, memoria, uploads e cronometra o `docker stop -t 60` ocioso e com job, e remove os containers no fim.

E2E local com o front (Playwright, mapa §5): `bash scripts/e2e-local.sh java` ou `bash scripts/e2e-local.sh django` (baseline). Sobe Postgres 18 com o `schema-prod.sql` e o `scripts/seed-e2e.sql` (sintetico), o backend na 8000 (o default do proxy de `/api` do Vite), o Vite do worktree `clama-frontend-etapa7` na 5173 e o `scripts/e2e-stub.py` (Claude, Resend com caixa de saida em `GET /outbox`, Turnstile). Nenhum perfil de mock no codigo: o Java chega no stub pela config que ja existe (`--clama.anthropic.url`, `--clama.email.resend-url`, `TURNSTILE_VERIFY_URL`). Args extras vao para o `playwright test`; `E2E_SO_SUBIR=1` so sobe e espera. Detalhes no cabecalho do script.
