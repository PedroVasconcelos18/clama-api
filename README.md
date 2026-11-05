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

## Configuracao

- `DATABASE_URL` (formato Railway) vira o DataSource via `DatabaseUrlConfig`, fora do perfil `test`. Sem ela vale a config padrao do Spring.
- `DJANGO_SECRET_KEY`, `FIELD_ENCRYPTION_KEY` e `FREEMIUM_HASH_SECRET` sao obrigatorias e nao tem default. Se faltar alguma, ou se vier em branco, a aplicacao nao sobe. Os valores de teste ficam so em `src/test/resources/application-test.yml`.

## Transacao por requisicao

O Django roda com `ATOMIC_REQUESTS=True`: a view inteira e uma transacao. Aqui nao ha transacao implicita (`open-in-view: false`, controllers sem `@Transactional`). O padrao e este:

1. O controller valida a entrada fora da transacao (`CamposDrf`). Assim, um 400 de validacao nao abre transacao.
2. O trabalho de banco roda numa transacao so: um `TransactionTemplate` no controller (`tx.execute(...)` ou `tx.executeWithoutResult(...)`) quando junta varios passos, como autenticar (que pode regravar o hash da senha) e emitir tokens, ou travar a linha com `select ... for update` na troca de senha. Quando e um passo so, vale o `@Transactional` do servico (`ServicoDeTokens.rotacionar` e `revogarDoUsuario`, no refresh e no logout).
3. Os cookies (`CookiesDeAuth.gravar` e `apagar`) so sao escritos depois que a transacao retorna (o template ou o metodo do servico), ou seja, depois do commit.

O motivo e que um cookie ja escrito na resposta nao volta atras. Se o commit falhar, por exemplo num conflito na rotacao do refresh, o cliente nao pode ficar com um par de tokens que o banco nao registrou, nem perder a sessao por um logout que nao aconteceu. Uma excecao dentro do template faz rollback e vira resposta pelo `PastoralExceptionHandler`, sem cookie nenhum.

## Flyway e fila de jobs (producao)

O Flyway agora roda no boot, so com migrations **aditivas** (tabelas novas, nunca `ALTER`/`DROP` em tabela do Django) ate o cutover estar estavel. Na primeira subida contra o banco do Django ele faz baseline na versao 0 e cria `flyway_schema_history` e `scheduled_tasks` (a fila do db-scheduler, que substitui Celery+Redis). O usuario do banco precisa de permissao `CREATE`. O scheduler so inicia depois das migrations.

O Django ignora essas tabelas, entao voltar para ele e seguro. Mas jobs pendentes em `scheduled_tasks` ficam invisiveis para o Django: no rollback eles nao rodam.
