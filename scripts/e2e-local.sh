#!/bin/bash
#
# E2E local (Etapa 9, tarefas 4 e 5; mapa §5.2): Postgres 18 com o schema de produção e o seed sintético, um backend
# (Java ou Django, o mesmo banco e a mesma porta), os serviços externos de mentira e o Vite do worktree do front;
# depois roda o Playwright. Tudo em localhost. NUNCA fala com o Railway nem com produção.
#
#   bash scripts/e2e-local.sh java                      # suíte inteira contra o Java
#   bash scripts/e2e-local.sh django                    # a mesma suíte contra o Django (baseline)
#   bash scripts/e2e-local.sh java tests/e2e/freemium-contra-backend.spec.ts   # args vão para o playwright test
#   E2E_SO_SUBIR=1 bash scripts/e2e-local.sh java       # só sobe e espera (Ctrl-C derruba); front em :5173
#
# Variáveis: E2E_SEM_BUILD=1 (Java: usa o target/clama-api.jar que existir), E2E_JAVA_HOME (padrão
# /opt/homebrew/opt/openjdk@27), E2E_SAIDA (logs; padrão um mktemp), E2E_FRONT (padrão ../clama-frontend-etapa7),
# E2E_DJANGO (padrão ../clama-backend).
#
# Portas: Postgres 55432, backend 8000 (o default do proxy de /api no vite.config.ts, então o front não precisa de
# .env.local), Vite 5173, stub 59999. O script recusa subir se alguma já estiver ocupada.
#
# Serviços externos: scripts/e2e-stub.py (Claude, Resend com caixa de saída em GET /outbox, Turnstile sempre ok).
# Os dois backends chegam nele pela config que já existe, sem perfil de mock no código:
#   Java:   ANTHROPIC_API_KEY + --clama.anthropic.url, RESEND_API_KEY + --clama.email.resend-url,
#           TURNSTILE_SECRET_KEY + TURNSTILE_VERIFY_URL
#   Django: ANTHROPIC_API_KEY + ANTHROPIC_BASE_URL (o SDK lê), TURNSTILE_* iguais, e-mail pelo Anymail/Resend no
#           scripts/e2e_django_settings.py (config.settings.local com cache em memória e Celery eager, sem Redis)
# Mercado Pago fica sem token: nenhum spec gera Pix.
#
# Os backends sobem com `env -i`: nada do shell (DATABASE_URL, SENTRY_DSN, chaves reais) vaza para eles.

set -u -o pipefail
unset DATABASE_URL

BACKEND="${1:-}"
case "$BACKEND" in
java | django) shift ;;
*) echo "uso: $0 <java|django> [args do playwright test]" >&2; exit 2 ;;
esac

RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
FRONT="$(cd "${E2E_FRONT:-$RAIZ/../clama-frontend-etapa7}" && pwd)"
DJANGO="$(cd "${E2E_DJANGO:-$RAIZ/../clama-backend}" && pwd)"
[ "$(basename "$FRONT")" = clama-frontend ] && { echo "o front do e2e e o worktree, nunca $FRONT" >&2; exit 1; }
export DOCKER_HOST="${DOCKER_HOST:-unix://$HOME/.colima/default/docker.sock}"
case "$DOCKER_HOST" in
unix://*) ;;
*) echo "DOCKER_HOST=$DOCKER_HOST nao e um socket unix local; o e2e so roda no docker local" >&2; exit 1 ;;
esac

PG=clama-e2e
P_PG=55432
P_API=8000
P_FRONT=5173
P_STUB=59999
DB_URL="postgres://postgres:e2e@localhost:$P_PG/clama"
STUB="http://127.0.0.1:$P_STUB"
FRONT_URL="http://localhost:$P_FRONT"
SAIDA="${E2E_SAIDA:-$(mktemp -d -t e2e-local)}"
mkdir -p "$SAIDA"
PIDS=()

limpar() {
	for p in ${PIDS[@]+"${PIDS[@]}"}; do kill "$p" 2>/dev/null; done # bash 3.2 do macOS: array vazio + set -u
	wait 2>/dev/null
	docker rm -f "$PG" >/dev/null 2>&1
}

esperar() { # esperar <rotulo> <url> <segundos> <log>
	for _ in $(seq 1 $(($3 * 5))); do
		[ "$(curl -s -o /dev/null -w '%{http_code}' "$2")" = 200 ] && return 0
		sleep 0.2
	done
	echo "$1 nao respondeu 200 em $3 s: $4" >&2
	exit 1
}

for porta in $P_PG $P_API $P_FRONT $P_STUB; do
	if lsof -nP -iTCP:"$porta" -sTCP:LISTEN >/dev/null 2>&1; then
		echo "porta $porta ocupada; derrube o que estiver nela" >&2
		exit 1
	fi
done
# só agora: com uma porta ocupada (outro e2e no ar), o trap derrubaria o container dele
trap limpar EXIT
trap 'exit 130' INT TERM
echo "backend: $BACKEND  front: $FRONT  logs: $SAIDA"

# 1. Java: o jar do working tree (antes de subir qualquer coisa)
JAVA_HOME="${E2E_JAVA_HOME:-/opt/homebrew/opt/openjdk@27}" # nao herda o JAVA_HOME do shell (pode ser o 25)
if [ "$BACKEND" = java ] && [ "${E2E_SEM_BUILD:-}" != 1 ]; then
	(cd "$RAIZ" && JAVA_HOME="$JAVA_HOME" ./mvnw -q -B package -DskipTests) >"$SAIDA/build.log" 2>&1 ||
		{ echo "build falhou: $SAIDA/build.log" >&2; exit 1; }
fi

# 2. Postgres 18: schema de produção (sem dados) + seed sintético
docker rm -f "$PG" >/dev/null 2>&1
docker run -d --name "$PG" -e POSTGRES_PASSWORD=e2e -e POSTGRES_DB=clama -p "127.0.0.1:$P_PG:5432" postgres:18 >/dev/null
for _ in $(seq 1 100); do
	docker exec "$PG" pg_isready -U postgres -d clama -h 127.0.0.1 >/dev/null 2>&1 && break
	sleep 0.3
done
sql() { docker exec -i "$PG" psql -U postgres -d clama -v ON_ERROR_STOP=1 -qAt "$@"; }
sql <"$RAIZ/src/test/resources/db/schema-prod.sql" >/dev/null || { echo "schema-prod falhou" >&2; exit 1; }
sql <"$RAIZ/scripts/seed-e2e.sql" >/dev/null || { echo "seed falhou" >&2; exit 1; }

# 3. serviços externos de mentira
python3 -u "$RAIZ/scripts/e2e-stub.py" "$P_STUB" >"$SAIDA/stub.log" 2>&1 &
PIDS+=($!)
esperar stub "$STUB/outbox" 10 "$SAIDA/stub.log"

# 4. backend na 8000. Segredos de mentira; FIELD_ENCRYPTION_KEY é a do application-test.yml
COMUM=(
	PATH="/usr/bin:/bin:/usr/sbin:/sbin" HOME="$HOME" LANG=en_US.UTF-8 TZ=America/Sao_Paulo
	DATABASE_URL="$DB_URL"
	FIELD_ENCRYPTION_KEY=-dPyxte5HDDyxBQExFzkphbnXt2xhs9FWlMLPFQpeuw=
	FREEMIUM_HASH_SECRET=e2e
	FREEMIUM_TEMP_PWD_KEY=xTEmld5LzkZz1xCiGvWLbeATZxQjTDx7z5SIRHRxbbg=
	DJANGO_SECRET_KEY=e2e-segredo-local-0123456789abcdef0123456789abcdef
	MERCADOPAGO_WEBHOOK_SECRET=e2e WORDPRESS_WEBHOOK_SECRET=e2e
	ANTHROPIC_API_KEY=sk-ant-e2e
	TURNSTILE_SECRET_KEY=e2e TURNSTILE_VERIFY_URL="$STUB/turnstile/v0/siteverify"
	AUTH_COOKIE_SECURE=false
	FRONTEND_URL="$FRONT_URL" BACKEND_PUBLIC_URL="$FRONT_URL"
	DJANGO_CSRF_TRUSTED_ORIGINS="$FRONT_URL,http://127.0.0.1:$P_FRONT"
	DJANGO_ALLOWED_HOSTS=localhost,127.0.0.1
)
if [ "$BACKEND" = java ]; then
	(cd "$RAIZ" && exec env -i "${COMUM[@]}" PORT=$P_API RESEND_API_KEY=re_e2e \
		"$JAVA_HOME/bin/java" -jar target/clama-api.jar \
		--clama.anthropic.url="$STUB" --clama.email.resend-url="$STUB/") >"$SAIDA/backend.log" 2>&1 &
else
	(cd "$DJANGO" && exec env -i "${COMUM[@]}" DJANGO_SETTINGS_MODULE=e2e_django_settings PYTHONPATH="$RAIZ/scripts" PYTHONDONTWRITEBYTECODE=1 \
		E2E_STUB_URL="$STUB/" ANTHROPIC_BASE_URL="$STUB" \
		.venv/bin/python manage.py runserver "127.0.0.1:$P_API" --noreload) >"$SAIDA/backend.log" 2>&1 &
fi
PIDS+=($!)
esperar "$BACKEND" "http://localhost:$P_API/api/health/" 90 "$SAIDA/backend.log"

# 5. Vite do worktree; as VITE_* vão pelo ambiente (nenhum .env.local no worktree)
(cd "$FRONT" && exec env VITE_API_URL= VITE_DEV_API_PROXY_TARGET="http://localhost:$P_API" \
	VITE_TURNSTILE_SITE_KEY=1x00000000000000000000AA \
	node_modules/.bin/vite --port $P_FRONT --strictPort) >"$SAIDA/vite.log" 2>&1 &
PIDS+=($!)
esperar vite "$FRONT_URL/" 60 "$SAIDA/vite.log"

if [ "${E2E_SO_SUBIR:-}" = 1 ]; then
	echo "no ar: $FRONT_URL (backend $BACKEND em :$P_API, caixa de saida $STUB/outbox). Ctrl-C derruba."
	wait
	exit 0
fi

# 6. Playwright (os artefatos vão para $SAIDA: o test-results/.last-run.json do worktree é versionado)
cd "$FRONT"
BASE_URL="$FRONT_URL" E2E_OUTBOX_URL="$STUB/outbox" \
	npx playwright test --reporter=list --output "$SAIDA/test-results" "$@" 2>&1 | tee "$SAIDA/playwright.log"
RC=${PIPESTATUS[0]}
echo "playwright saiu com $RC; logs em $SAIDA"
exit "$RC"
