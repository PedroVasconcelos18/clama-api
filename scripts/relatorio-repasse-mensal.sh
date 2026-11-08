#!/bin/bash
#
# Emissão mensal do repasse às instituições parceiras.
#
# Disparado pelo LaunchAgent me.clama.relatorio-repasse (todo dia 28, 10h).
# Rodar à mão: bash scripts/relatorio-repasse-mensal.sh
#
# Por que launchd e não cron: num notebook, cron pula o job se a máquina
# estiver dormindo no dia 28 e não roda depois. launchd executa o job perdido
# assim que a máquina acorda.
#
# Por que `railway run` e não uma conexão direta: nome e e-mail do doador são
# EncryptedCharField (Fernet). SQL direto devolve cifra — só o ORM com a
# FIELD_ENCRYPTION_KEY do ambiente de produção descriptografa.

set -u -o pipefail

# Versão do script com chave de motor (etapa 8, D12). Cópia versionada: o script
# em uso no Mac (scripts/relatorio-repasse-mensal.sh na raiz de clama) e o job do
# launchd NÃO são alterados por este arquivo; a troca é um item da virada.
#
#   MOTOR=django (padrão)  igual ao script atual: railway run ... manage.py
#   MOTOR=java             railway run ... java -jar clama-api.jar <comando>
#                          (o jar precisa ser do MESMO commit do deploy:
#                           ./mvnw -q package -DskipTests no clama-api)
#
# Tudo abaixo com padrão é sobrescrevível pelo ambiente; os padrões são os de
# produção. Os ganchos (HOJE, DESDE, ATE, MES, RELATORIOS, EXEC_PREFIX) existem
# para o teste (RelatorioMensalIT): ninguém precisa defini-los na rotina.
MOTOR="${MOTOR:-django}"
BACKEND="${BACKEND:-/Users/pedro/projects/clama/clama-backend}"   # dir linkado no Railway
API="${API:-/Users/pedro/projects/clama/clama-api}"               # idem, no motor java
RELATORIOS="${RELATORIOS:-/Users/pedro/projects/clama/relatorios}"
PYTHON="$BACKEND/.venv/bin/python"
RAILWAY="${RAILWAY:-/opt/homebrew/bin/railway}"
# O launchd não tem PATH, e o `java` do PATH do Mac é o 25 (o jar é 27).
JAVA="${JAVA:-/opt/homebrew/opt/openjdk@27/bin/java}"
JAR="${JAR:-$API/target/clama-api.jar}"   # finalName fixo no pom.xml
SERVICO="${SERVICO:-web}"                 # serviço do Railway (o do Java, depois da virada)

case "$MOTOR" in
    django) DIRETORIO="$BACKEND" ;;
    java)   DIRETORIO="$API" ;;
    *) echo "MOTOR inválido: '$MOTOR' (use django ou java)"; exit 2 ;;
esac

# Uma emissão por instituição parceira. Antes daqui havia um único
# INSTITUICAO="Renais", e ele NÃO dava erro com a segunda associação cadastrada:
# "Renais" (plural) casa só com a de Juiz de Fora, porque a de Mar de Espanha é
# "Renal" (singular). Ou seja, o script terminava com sucesso, notificava
# "relatório pronto" e simplesmente não emitia nada para Mar de Espanha —
# repasse devido que não aparece em lugar nenhum. Falha silenciosa é pior que
# erro, então a lista é explícita: quem não está aqui não é cobrado.
#
# Os termos são a parte DISCRIMINANTE do nome, não o nome todo:
# `relatorio_repasse` casa por substring, aborta se o termo pegar mais de uma
# instituição e emite a errada (ou nenhuma) se pegar de menos. Cada termo abaixo
# tem que casar com exatamente uma. Ao cadastrar uma instituição nova,
# acrescente aqui o trecho que a distingue — ou o UUID, que nunca é ambíguo.
INSTITUICOES=(
    "Juiz de Fora"      # Assoc. dos Doentes Renais Crônicos e Transplantados
    "Mar de Espanha"    # Assoc. Renal de Mar de Espanha e Adjacências
    "Pela Vida"         # KM’s Pela Vida | NÃO DESISTA (cadastrada em 22/09, ficou 1 mês fora daqui)
)

MES="${MES:-$(date +%Y-%m)}"
HOJE="${HOJE:-$(date +%Y-%m-%d)}"

# Ciclo do repasse: dia 28 do mês anterior até dia 27 do mês corrente, os dois
# inclusivos (o comando trata --desde/--ate como inclusivos; "28 a 28" contaria
# o dia 28 em dois ciclos). Rodando antes do dia 28, o ciclo corrente ainda não
# fechou, então emite o anterior. -v1d antes do -v-Nm evita o estouro de
# "31 de março menos um mês".
if [ -n "${DESDE:-}" ] && [ -n "${ATE:-}" ]; then
    :   # ciclo informado de fora (teste)
elif [ "$(date +%-d)" -ge 28 ]; then
    DESDE="$(date -v1d -v-1m +%Y-%m)-28"
    ATE="$(date +%Y-%m)-27"
else
    DESDE="$(date -v1d -v-2m +%Y-%m)-28"
    ATE="$(date -v1d -v-1m +%Y-%m)-27"
fi
LOG="$RELATORIOS/log-$MES.txt"

mkdir -p "$RELATORIOS"
cd "$DIRETORIO" || { echo "$DIRETORIO não existe"; exit 1; }

exec > >(tee -a "$LOG") 2>&1
echo ""
echo "==================== $(date '+%d/%m/%Y %H:%M') ===================="

if [ "$MOTOR" = java ]; then
    # railway run injeta as variáveis do serviço, como no Django. EXEC_PREFIX
    # (mesmo vazio) troca o prefixo: o teste chama o jar direto, sem railway.
    if [ "${EXEC_PREFIX+set}" = set ]; then
        read -r -a PREFIXO <<< "$EXEC_PREFIX"
    else
        PREFIXO=("$RAILWAY" run --service "$SERVICO" --)
    fi
    executar() { ${PREFIXO[@]+"${PREFIXO[@]}"} "$JAVA" -jar "$JAR" "$@"; }
else
    executar() { "$RAILWAY" run --service "$SERVICO" -- "$PYTHON" manage.py "$@"; }
fi

# Termo -> pedaço de nome de arquivo ("Mar de Espanha" -> "mar-de-espanha").
apelido() { echo "$1" | tr '[:upper:]' '[:lower:]' | tr ' ' '-'; }

# 1) Auditoria primeiro: se o ledger estiver furado, o relatório está errado.
#    Sem --instituicao de propósito: varre o ledger inteiro, todas as parceiras.
#    Sai 1 quando acha algo crítico — não é falha do script.
echo ""
echo "--- auditoria do ledger (todas as instituições) ---"
executar auditar_repasses --csv "$RELATORIOS/auditoria-ledger-$HOJE.csv"
AUDITORIA=$?

# 2) Repasses do ciclo, uma instituição por vez. Uma instituição que falha não
#    impede a emissão das outras.
RELATORIO=0
FALHARAM=()
for INSTITUICAO in "${INSTITUICOES[@]}"; do
    APELIDO=$(apelido "$INSTITUICAO")

    echo ""
    echo "--- relatório para a instituição: $INSTITUICAO ($DESDE a $ATE) ---"
    executar relatorio_repasse \
        --instituicao "$INSTITUICAO" \
        --desde "$DESDE" --ate "$ATE" \
        --saida "$RELATORIOS/relatorio-repasse-$APELIDO-$HOJE.csv"
    if [ $? -ne 0 ]; then
        RELATORIO=1
        FALHARAM+=("$INSTITUICAO")
    fi

    # 3) Cópia interna com e-mail e id de pagamento, pra conciliar com o extrato.
    echo ""
    echo "--- auditoria interna: $INSTITUICAO ---"
    executar relatorio_repasse \
        --instituicao "$INSTITUICAO" \
        --desde "$DESDE" --ate "$ATE" \
        --auditoria \
        --saida "$RELATORIOS/auditoria-INTERNA-$APELIDO-$HOJE.csv"
done

# 4) A lista acima é manual e já esqueceu uma instituição uma vez. Se o banco
#    tiver mais instituições do que a lista, alguém está sem relatório.
if [ "$MOTOR" = java ]; then
    # `instituicoes` imprime uma linha por instituição (id<TAB>nome)
    NO_BANCO=$(executar instituicoes 2>/dev/null | wc -l | tr -d ' ')
else
    NO_BANCO=$(executar shell -c "from clama.instituicoes.models import Instituicao; print(Instituicao.objects.count())" 2>/dev/null | tail -1)
fi
FORA_DA_LISTA=0
if [ "$NO_BANCO" != "${#INSTITUICOES[@]}" ]; then
    FORA_DA_LISTA=1
    echo ""
    echo "!!! O banco tem '$NO_BANCO' instituições e a lista INSTITUICOES tem ${#INSTITUICOES[@]}."
fi

if [ $FORA_DA_LISTA -ne 0 ]; then
    RESUMO="Banco tem $NO_BANCO instituições, script cobre ${#INSTITUICOES[@]} — atualize INSTITUICOES"
elif [ $AUDITORIA -ne 0 ]; then
    RESUMO="Ledger com achado CRÍTICO — confira antes de repassar"
elif [ $RELATORIO -ne 0 ]; then
    RESUMO="Falha em ${#FALHARAM[@]} de ${#INSTITUICOES[@]} instituições (${FALHARAM[*]}) — veja $LOG"
else
    RESUMO="Relatórios das ${#INSTITUICOES[@]} instituições prontos em clama/relatorios"
fi

echo ""
echo ">>> $RESUMO"
osascript -e "display notification \"$RESUMO\" with title \"Repasse — dia 28\"" 2>/dev/null || true
