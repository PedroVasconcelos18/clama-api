#!/bin/bash
#
# Ensaio local do deploy do Java (Etapa 9, tarefa 3; mapa §7 T3, §2.9-2.11, #14, #15, #16).
#
# Tudo roda no docker local (colima). NUNCA fala com o Railway nem com produção: o banco é um Postgres 18 num
# container, carregado com src/test/resources/db/schema-prod.sql (só o schema, sem dado nenhum), a Claude é um
# stub local e os segredos são gerados na hora.
#
#   bash scripts/ensaio-deploy.sh
#
# Passos: build da imagem; Postgres 18 com SSL (o DatabaseUrlConfig põe sslmode=require para host que não é
# localhost, como no Railway); boot com PORT=8123 e -m 1536m; Flyway (baseline 0 + scheduled_tasks); health com o
# Host do healthcheck do Railway e Host proibido; RSS depois do boot e depois de carga; upload .docx (MIME) e upload
# acima de 101 MB; docker stop -t 60 ocioso; docker stop -t 60 com um gerar-oracao rodando contra a Claude lenta
# (curto: termina dentro da espera da fila; longo: passa dela) e se o job longo volta no boot seguinte.
#
# Variáveis: ENSAIO_SAIDA (diretório dos logs; padrão um mktemp), ENSAIO_SEM_BUILD=1 (reusa clama-api:ensaio),
# ENSAIO_SEM_RETOMADA=1 (pula a espera de ~2 min pela retomada do job longo).
# Sai com 1 se alguma checagem falhar. Os containers e a rede são removidos no fim, com ou sem falha.

set -u -o pipefail
unset DATABASE_URL # nunca herdar um DATABASE_URL do shell

RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
export DOCKER_HOST="${DOCKER_HOST:-unix://$HOME/.colima/default/docker.sock}"
case "$DOCKER_HOST" in
unix://*) ;;
*) echo "DOCKER_HOST=$DOCKER_HOST nao e um socket unix local; o ensaio so roda no docker local" >&2; exit 1 ;;
esac
IMG=clama-api:ensaio
REDE=clama-ensaio
PG=clama-ensaio-pg
APP=clama-ensaio-app
STUB=clama-ensaio-claude
PORTA=8123        # PORT dentro do container (diferente de 8080 de propósito)
LOCAL=18123       # porta no Mac
URL="http://localhost:$LOCAL"
SAIDA="${ENSAIO_SAIDA:-$(mktemp -d -t ensaio-deploy)}"
mkdir -p "$SAIDA"
FALHAS=0
RESUMO="$SAIDA/resumo.txt"
: >"$RESUMO"

limpar() {
	docker rm -f "$APP" "$STUB" "$PG" >/dev/null 2>&1
	docker network rm "$REDE" >/dev/null 2>&1
	rm -f "$SAIDA/grande.docx"
}
trap limpar EXIT

agora() { python3 -c 'import time; print(f"{time.time():.2f}")'; }
dif() { awk -v a="$1" -v b="$2" 'BEGIN { printf "%.1f", b - a }'; }
anotar() { echo "$*" | tee -a "$RESUMO"; }
checar() { # checar <nome> <condicao-shell>
	if eval "$2"; then anotar "PASS  $1"; else anotar "FAIL  $1"; FALHAS=$((FALHAS + 1)); fi
}
sql() { docker exec -i "$PG" psql -U postgres -d clama -v ON_ERROR_STOP=1 -qAt "$@"; }
rss() { docker exec "$APP" sh -c 'grep VmRSS /proc/1/status' | awk '{ printf "%.0f MiB", $2 / 1024 }'; }
uuid() { python3 -c 'import uuid; print(uuid.uuid4())'; }

# segredos de mentira, no formato certo (Fernet = 32 bytes em base64 urlsafe)
FIELD_ENCRYPTION_KEY="$(openssl rand -base64 32 | tr '+/' '-_')"
DJANGO_SECRET_KEY="$(openssl rand -hex 32)"
SENHA_ADMIN="Ensaio-$(openssl rand -hex 8)"
ADMIN=admin@ensaio.test

# Claude de mentira: dorme ATRASO segundos e devolve uma Message válida
STUB_PY='
import json, os, time
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
ATRASO = float(os.environ.get("ATRASO", "0"))
class H(BaseHTTPRequestHandler):
    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length") or 0))
        print(f"stub: POST {self.path} dormindo {ATRASO}s", flush=True)
        time.sleep(ATRASO)
        corpo = json.dumps({"id": "msg_ensaio", "type": "message", "role": "assistant", "model": "claude-ensaio",
            "content": [{"type": "text", "text": "Oração do ensaio."}], "stop_reason": "end_turn",
            "stop_sequence": None, "usage": {"input_tokens": 1, "output_tokens": 1}}).encode()
        try:
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(corpo)))
            self.end_headers()
            self.wfile.write(corpo)
            print("stub: respondeu", flush=True)
        except OSError as e:
            print(f"stub: cliente sumiu ({e})", flush=True)
ThreadingHTTPServer(("0.0.0.0", 9999), H).serve_forever()
'

subir_stub() { # subir_stub <atraso>
	docker rm -f "$STUB" >/dev/null 2>&1
	docker run -d --name "$STUB" --network "$REDE" -e ATRASO="$1" python:3.12.3-slim python -u -c "$STUB_PY" >/dev/null
}

# sobe o app e espera o health; ecoa os segundos até o 1o 200
subir_app() { # subir_app <rotulo>
	docker rm -f "$APP" >/dev/null 2>&1
	local t0 t1
	t0=$(agora)
	docker run -d --name "$APP" --network "$REDE" -m 1536m -p "127.0.0.1:$LOCAL:$PORTA" \
		-e PORT="$PORTA" \
		-e DATABASE_URL="postgres://postgres:ensaio@$PG:5432/clama" \
		-e FIELD_ENCRYPTION_KEY="$FIELD_ENCRYPTION_KEY" -e DJANGO_SECRET_KEY="$DJANGO_SECRET_KEY" \
		-e FREEMIUM_HASH_SECRET=ensaio -e MERCADOPAGO_WEBHOOK_SECRET=ensaio -e WORDPRESS_WEBHOOK_SECRET=ensaio \
		-e ANTHROPIC_API_KEY=sk-ant-ensaio -e CLAMA_ANTHROPIC_URL="http://$STUB:9999" \
		-e DJANGO_ALLOWED_HOSTS=localhost,127.0.0.1 \
		"$IMG" >/dev/null || return 1
	for _ in $(seq 1 600); do
		if [ "$(curl -s -o /dev/null -w '%{http_code}' "$URL/api/health/")" = 200 ]; then
			t1=$(agora)
			dif "$t0" "$t1"
			return 0
		fi
		if [ "$(docker inspect -f '{{.State.Running}}' "$APP" 2>/dev/null)" != true ]; then break; fi
		sleep 0.2
	done
	docker logs "$APP" >"$SAIDA/app-$1.log" 2>&1
	return 1
}

# docker stop -t 60 cronometrado; ecoa "<segundos> <exit code>" e guarda o log
parar_app() { # parar_app <rotulo>
	local t0 t1
	t0=$(agora)
	docker stop -t 60 "$APP" >/dev/null
	t1=$(agora)
	docker logs "$APP" >"$SAIDA/app-$1.log" 2>&1
	echo "$(dif "$t0" "$t1") $(docker inspect -f '{{.State.ExitCode}}' "$APP")"
}

# login do admin: deixa ACESSO e CSRF prontos para os POSTs
logar() {
	local jar="$SAIDA/cookies.txt"
	rm -f "$jar"
	curl -s -c "$jar" -o /dev/null -H 'Content-Type: application/json' \
		-d "{\"email\":\"$ADMIN\",\"password\":\"$SENHA_ADMIN\"}" "$URL/api/admin/auth/login/"
	curl -s -b "$jar" -c "$jar" -o /dev/null "$URL/api/csrf/"
	ACESSO=$(awk '$6 == "clama_access" { print $7 }' "$jar")
	CSRF=$(awk '$6 == "csrftoken" { print $7 }' "$jar")
}
post_admin() { # post_admin <caminho> [args do curl...]
	local caminho="$1"; shift
	curl -s -H "Cookie: clama_access=$ACESSO; csrftoken=$CSRF" -H "X-CSRFToken: $CSRF" "$@" "$URL$caminho"
}

# pedido em erro sem oração: o "reenviar" do admin regera (gerar-oracao pelo caminho real da Fila)
semear() {
	local plano prompt
	plano=$(uuid); prompt=$(uuid); P_CURTO=$(uuid); P_LONGO=$(uuid)
	sql <<-SQL
	insert into plans_plan values (now(), now(), '$plano', 'Ensaio', 1000, '', 'simples', 0, true, true);
	insert into prompts_prompttemplate values (now(), now(), '$prompt', 'Ensaio', 1, 'system', '{"simples": "curta"}', true);
	insert into orders_pedido (created_at, updated_at, id, nome, email, telefone, sexo, pedido_oracao, oracao_gerada,
	  valor_centavos, canal_entrega, status, plano_id, consent_aceito, consent_versao, last_error, retry_count,
	  whatsapp_message_id, cpf_cnpj, eh_gratuito, device_hash)
	select now(), now(), id::uuid, 'Ensaio', 'ensaio@clama.test', '', '', 'paz', '', 1000, 'email', 'erro', '$plano',
	  true, '', '', 0, '', '', false, ''
	from (values ('$P_CURTO'), ('$P_LONGO')) v(id);
	SQL
}
status_pedido() { sql -c "select status from orders_pedido where id = '$1'"; }

# regera o pedido, espera a chamada chegar no stub e dá SIGTERM; ecoa "<segundos> <exit code>"
parar_com_job() { # parar_com_job <pedido> <rotulo>
	post_admin "/api/admin/pedidos/$1/reenviar/" -X POST -o /dev/null
	for _ in $(seq 1 100); do
		docker logs "$STUB" 2>&1 | grep -q 'stub: POST' && break
		sleep 0.2
	done
	sleep 1
	parar_app "$2"
}

echo "Saida: $SAIDA"

# 1. imagem
if [ "${ENSAIO_SEM_BUILD:-}" != 1 ]; then
	t0=$(agora)
	docker build -t "$IMG" "$RAIZ" >"$SAIDA/build.log" 2>&1 || { echo "build falhou: $SAIDA/build.log"; exit 1; }
	anotar "build: $(dif "$t0" "$(agora)") s"
fi

# 2. Postgres 18 com SSL (snakeoil da imagem) e o schema de produção
limpar
docker network create "$REDE" >/dev/null
docker run -d --name "$PG" --network "$REDE" -e POSTGRES_PASSWORD=ensaio -e POSTGRES_DB=clama postgres:18 \
	-c ssl=on -c ssl_cert_file=/etc/ssl/certs/ssl-cert-snakeoil.pem \
	-c ssl_key_file=/etc/ssl/private/ssl-cert-snakeoil.key >/dev/null
PG_PRONTO=
for _ in $(seq 1 100); do
	docker exec "$PG" pg_isready -U postgres -d clama -h 127.0.0.1 >/dev/null 2>&1 && { PG_PRONTO=1; break; }
	sleep 0.3
done
[ -n "$PG_PRONTO" ] || { docker logs "$PG" >"$SAIDA/pg.log" 2>&1; echo "Postgres nao ficou pronto em 30 s: $SAIDA/pg.log" >&2; exit 1; }
sql <"$RAIZ/src/test/resources/db/schema-prod.sql" >/dev/null
checar "schema-prod carregado, sem flyway nem scheduled_tasks" \
	'[ "$(sql -c "select to_regclass('"'public.scheduled_tasks'"') is null and to_regclass('"'public.flyway_schema_history'"') is null")" = t ]'
subir_stub 0

# 3-4. boot
BOOT=$(subir_app boot) || { anotar "FAIL  boot (log em $SAIDA/app-boot.log)"; exit 1; }
anotar "boot até o 1o 200 do health: $BOOT s ($(docker logs "$APP" 2>&1 | grep -o 'Started ClamaApiApplication in [0-9.]* seconds' | head -1))"
checar "java é o PID 1 (CMD exec)" '[ "$(docker exec "$APP" sh -c "tr \"\\0\" \" \" </proc/1/cmdline")" = "java -jar /app/clama-api.jar " ]'
checar "roda como usuário não-root" '[ "$(docker exec "$APP" id -u)" != 0 ]'
checar "ouve em \$PORT=$PORTA" '[ "$(docker logs "$APP" 2>&1 | grep -c "Tomcat started on port $PORTA")" -gt 0 ]'
checar "conexão ao banco com sslmode=require" '[ "$(sql -c "select count(*) from pg_stat_ssl s join pg_stat_activity a using (pid) where a.datname = '"'clama'"' and s.ssl and a.backend_type = '"'client backend'"' and a.application_name <> '"'psql'"'")" -gt 0 ]'
checar "Flyway: baseline 0 + V1" '[ "$(sql -c "select string_agg(version || '"':'"' || type || '"':'"' || success, '"','"' order by installed_rank) from flyway_schema_history")" = "0:BASELINE:true,1:SQL:true" ]'
checar "scheduled_tasks criada" '[ "$(sql -c "select to_regclass('"'public.scheduled_tasks'"') is not null")" = t ]'

# 5. health
H_RAILWAY=$(curl -s -o /dev/null -w '%{http_code}' -H 'Host: healthcheck.railway.app' "$URL/api/health/")
H_RUIM=$(curl -s -o /dev/null -w '%{http_code}' -H 'Host: evil.com' "$URL/api/health/")
checar "health com Host healthcheck.railway.app = 200 ($H_RAILWAY)" '[ "$H_RAILWAY" = 200 ]'
checar "health com Host evil.com = 400 ($H_RUIM)" '[ "$H_RUIM" = 400 ]'
checar "HSTS com X-Forwarded-Proto: https" \
	'curl -s -D - -o /dev/null -H "X-Forwarded-Proto: https" "$URL/api/health/" | grep -qi "^strict-transport-security: max-age=60; includeSubDomains; preload"'

# 7. memória depois do boot
sleep 5
checar "sem 'Using generated security password' no log" '[ "$(docker logs "$APP" 2>&1 | grep -c "Using generated security password")" = 0 ]'
anotar "RSS depois do boot: $(rss) ($(docker stats --no-stream --format '{{.MemUsage}}' "$APP"))"

# admin (modo comando dentro do container, com o env do servico) e login
docker exec "$APP" java -jar /app/clama-api.jar create_clama_admin --email "$ADMIN" --password "$SENHA_ADMIN" \
	--nome Ensaio >"$SAIDA/create_clama_admin.log" 2>&1
RC_ADMIN=$?
checar "create_clama_admin no container (modo comando)" '[ "$RC_ADMIN" = 0 ]'
logar
checar "login do admin" '[ -n "$ACESSO" ] && [ -n "$CSRF" ]'

# 8. uploads: MIME do .docx (#15) e disco media gravável (#16)
printf 'PK\003\004ensaio' >"$SAIDA/a.docx"
for tipo in application/vnd.openxmlformats-officedocument.wordprocessingml.document application/octet-stream; do
	MIME=$(post_admin /api/admin/documentos/ -F nome=ensaio -F "arquivo=@$SAIDA/a.docx;type=$tipo" |
		python3 -c 'import json, sys; print(json.load(sys.stdin).get("tipo_mime"))')
	anotar "upload .docx enviado como $tipo -> tipo_mime $MIME"
done
checar ".docx gravado em /app/media" '[ -n "$(docker exec "$APP" find /app/media -name a.docx)" ]'

# upload acima de 101 MB (#14)
head -c $((102 * 1024 * 1024)) /dev/zero >"$SAIDA/grande.docx"
GRANDE=$(post_admin /api/admin/documentos/ -F nome=grande -F "arquivo=@$SAIDA/grande.docx" -o "$SAIDA/grande.out" \
	-w '%{http_code} %{size_upload}' 2>&1; echo " curl=$?")
anotar "upload de 102 MiB: http/bytes enviados/curl = $GRANDE; corpo: $(head -c 300 "$SAIDA/grande.out" 2>/dev/null)"
checar "app vivo depois do upload grande" '[ "$(curl -s -o /dev/null -w "%{http_code}" "$URL/api/health/")" = 200 ]'

# carga leve e RSS de novo
seq 1 600 | xargs -P 8 -I{} curl -s -o /dev/null "$URL/api/health/"
seq 1 200 | xargs -P 8 -I{} curl -s -o /dev/null "$URL/api/planos/"
seq 1 200 | xargs -P 8 -I{} curl -s -o /dev/null "$URL/api/instituicoes/"
seq 1 100 | xargs -P 4 -I{} curl -s -o /dev/null -H "Cookie: clama_access=$ACESSO" "$URL/api/admin/pedidos/"
anotar "RSS depois de ~1100 requisições e dos uploads: $(rss) ($(docker stats --no-stream --format '{{.MemUsage}}' "$APP"))"

# 6a. shutdown ocioso
read -r S_OCIOSO X_OCIOSO <<<"$(parar_app ocioso)"
anotar "docker stop -t 60 ocioso: $S_OCIOSO s, exit $X_OCIOSO"
checar "ocioso sai em < 5 s sem SIGKILL" '[ "$X_OCIOSO" != 137 ] && awk "BEGIN { exit !($S_OCIOSO < 5) }"'

# 6b. shutdown com job: Claude lenta de 10 s (termina com folga dentro da 1a espera da fila, 15 s)
semear
subir_stub 10
B=$(subir_app curto) || { anotar "FAIL  boot curto"; exit 1; }
logar
read -r S_CURTO X_CURTO <<<"$(parar_com_job "$P_CURTO" curto)"
anotar "docker stop -t 60 com job de 10 s: $S_CURTO s, exit $X_CURTO, pedido $(status_pedido "$P_CURTO")"
checar "job curto termina antes de sair (oracao_gerada) e cabe em 60 s" \
	'[ "$(status_pedido "$P_CURTO")" = oracao_gerada ] && [ "$X_CURTO" != 137 ]'

# 6c. Claude lenta de 45 s: passa das duas esperas da fila (2x15 s) e do timeout de 30 s do cliente
sql -c "delete from scheduled_tasks" # o enviar-oracao do cenário curto (falharia sem RESEND_API_KEY)
subir_stub 45
B=$(subir_app longo) || { anotar "FAIL  boot longo"; exit 1; }
logar
read -r S_LONGO X_LONGO <<<"$(parar_com_job "$P_LONGO" longo)"
FILA_LONGO=$(sql -c "select task_name || ' picked=' || picked || ' falhas=' || coalesce(consecutive_failures, 0) from scheduled_tasks where task_instance = 'gerar-oracao/$P_LONGO'")
anotar "docker stop -t 60 com job de 45 s: $S_LONGO s, exit $X_LONGO, pedido $(status_pedido "$P_LONGO"), fila: ${FILA_LONGO:-vazia}"
checar "job longo: sai em < 60 s sem SIGKILL e o job fica na fila" \
	'[ "$X_LONGO" != 137 ] && awk "BEGIN { exit !($S_LONGO < 60) }" && [ -n "$FILA_LONGO" ]'

# retomada no boot seguinte: falha com backoff (60 s) ou execução morta (6 heartbeats de 20 s, ~2 min)
if [ "${ENSAIO_SEM_RETOMADA:-}" != 1 ]; then
	subir_stub 0
	B=$(subir_app retomada) || { anotar "FAIL  boot retomada"; exit 1; }
	t0=$(agora)
	for _ in $(seq 1 150); do
		[ "$(status_pedido "$P_LONGO")" = oracao_gerada ] && break
		sleep 2
	done
	anotar "retomada do job longo: pedido $(status_pedido "$P_LONGO") $(dif "$t0" "$(agora)") s depois do boot"
	checar "job longo volta e termina no boot seguinte" '[ "$(status_pedido "$P_LONGO")" = oracao_gerada ]'
	parar_app retomada >/dev/null
fi
docker logs "$STUB" >"$SAIDA/stub.log" 2>&1

# 15. MIME do .docx no Python 3.12.3 de produção (Railpack não traz /etc/mime.types)
anotar "python:3.12.3-slim mimetypes.guess_type('a.docx') = $(docker run --rm python:3.12.3-slim python -c 'import mimetypes; print(mimetypes.guess_type("a.docx")[0])')"

echo
echo "== Resumo ($SAIDA) =="
cat "$RESUMO"
[ "$FALHAS" -eq 0 ] || { echo "$FALHAS checagem(ns) falharam"; exit 1; }
