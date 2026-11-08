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

## Modo comando e a rotina mensal de repasse

O jar tambem roda comandos pontuais (equivalentes aos `manage.py` do Django): `java -jar target/clama-api.jar <comando> [flags]`. Se o 1o argumento e um comando conhecido (`instituicoes`, `tarefa`, `relatorio_repasse`, `auditar_repasses`, `marcar_repasse_transferido`, `adotar_pedidos_orfaos`, `create_clama_admin`, `purgar_dados_blog_customer`, `lgpd_export`, `lgpd_delete`), o `main` sobe um contexto sem web, sem poller da fila, sem agenda e com o Flyway desligado, executa e sai com o exit code do comando. O stdout leva so o resultado (o log vai para o stderr), sempre em UTF-8.

Notas dos comandos de conta:
- `create_clama_admin --password <senha>` deixa a senha no historico do shell e no argv (`ps`); prefira omitir `--password` e digitar no prompt (duas vezes, sem eco).
- Sem terminal (pipe, redirecionamento), a senha vem de UMA linha do stdin, e ali nao ha como esconder o eco.
- `--superusuario` e o resgate para quando se perde o unico superusuario: cria o admin tambem com `is_superuser`. A tela do painel nunca cria superusuario.
- `--autor <id>` grava a criacao no `django_admin_log` em nome desse usuario (a tabela exige autor); sem ele so sai o log estruturado.

Notas dos comandos LGPD (`--email` e comparado na forma canonica: caixa, Gmail sem pontos e `+tag`):
- `lgpd_export --email <e> [--output-dir lgpd_exports]` grava um JSON (permissao 0600) com pedidos, contas, blog e se a pessoa consta na blacklist do freemium. Apague o arquivo depois de enviar ao titular.
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

`scripts/relatorio-repasse-mensal.sh` e a versao versionada do script do dia 28 (o script em uso no Mac e o launchd nao dependem dele ate a virada). `MOTOR=django` (padrao) faz o que o script atual faz; `MOTOR=java` chama `$JAVA -jar $JAR <comando>` dentro de `railway run --service $SERVICO --` e conta as instituicoes pelo comando `instituicoes` (`wc -l`) no lugar do `manage.py shell -c`. Variaveis: `MOTOR`, `JAVA` (padrao `/opt/homebrew/opt/openjdk@27/bin/java`, o launchd nao tem PATH), `JAR` (padrao `$API/target/clama-api.jar`), `API`, `SERVICO` (padrao `web`), `RELATORIOS`; ganchos de teste: `HOJE`, `MES`, `DESDE`+`ATE`, `EXEC_PREFIX` (substitui o `railway run`; vazio = chama o jar direto). O jar tem que ser do mesmo commit do deploy. Teste: `RelatorioMensalIT` (roda no `./mvnw verify`).

**Start command do servico no Railway: `java -jar target/clama-api.jar`, sem argumento posicional.** Um argumento que case com um comando faria o servidor virar um processo one-shot e sair.

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

No SIGTERM, a fila (db-scheduler, `shutdownMaxWait` 25 s) espera 25 s pelo que esta rodando, e a agenda (`ThreadPoolTaskScheduler`, `awaitTermination` 10 s) espera so a tarefa em andamento: tarefa futura nao roda no shutdown. **No Railway, o draining time do servico precisa ser >= 35 s**, senao o SIGKILL chega antes (orcamento: 25 s para a fila + 10 s para a agenda, que podem somar). Um job morto no meio (heartbeat de 20 s, 6 perdidos) e retomado por outra instancia em cerca de 2 min.
