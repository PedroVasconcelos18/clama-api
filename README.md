# clama-api

Backend Java/Spring Boot 4.1 (Java 27) do Clama. Pacote raiz `me.clama`, classe principal `me.clama.ClamaApplication`.

**A reescrita esta em andamento (onda 1, fundacao).** O codigo antigo continua no branch `migracao-java/integracao`.
Spec: `clama-docs/superpowers/specs/2026-11-08-clama-api-estrutura-java-idiomatica.md`.
Inventario de comportamentos: `clama-docs/superpowers/specs/2026-11-08-inventario-de-comportamentos.md`.

## Rodar local

Postgres 18 em `localhost:5432` (banco `clama`; usuario e senha `postgres`, ou `DB_USER`/`DB_PASSWORD`).

```bash
export JAVA_HOME=$(brew --prefix openjdk@27)
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

O perfil `local` carrega `src/main/resources/application-local.yml` (datasource local, log legivel).

## Testes

Precisam de Docker (Testcontainers, `postgres:18`). Com o colima:

```bash
colima start
export JAVA_HOME=$(brew --prefix openjdk@27)
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
./mvnw verify
```

`DATABASE_URL` e `SENTRY_DSN` sao excluidos do ambiente dos testes (surefire/failsafe), mas nao os exporte no shell mesmo assim.

## Variaveis de ambiente

| Variavel | Uso |
|---|---|
| `DATABASE_URL` | `postgres[ql]://usuario:senha@host:porta/banco` (Railway); vira `spring.datasource.*` (SSL exigido, exceto em localhost) |
| `PORT` | porta HTTP (padrao 8080) |
| `SENTRY_DSN` | DSN do Sentry (vazio desliga) |
| `SENTRY_ENVIRONMENT` | ambiente no Sentry (padrao `local`) |

`spring.datasource.*` explicito sempre vence o `DATABASE_URL`.

## Probes

- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness`
