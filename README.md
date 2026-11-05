# clama-api

Backend Java/Spring Boot (migracao do clama-backend Django). Pacote `me.clama.api`.

- Java 27: `export JAVA_HOME=/opt/homebrew/opt/openjdk` (sem fallback para 25 ate agora; Boot 4.1.2 e Testcontainers funcionam).
- Postgres de producao: **18.6** (`show server_version`). Os testes usam `postgres:18` com `src/test/resources/db/schema-prod.sql` (pg_dump --schema-only de producao).
- Testes precisam de Docker: `colima start` e

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
./mvnw test
```

- `DATABASE_URL` (formato Railway) vira o DataSource via `DatabaseUrlConfig`; sem ela vale a config padrao do Spring.
