"""Extrator unico da virada: le um banco RESTAURADO de um dump do Django (nunca o de producao), decifra a PII com a
FIELD_ENCRYPTION_KEY e grava UM JSON (modo 600) para o importador do clama-api (perfil "importacao").

Uso (python3 -I, venv com psycopg e cryptography):
  SRC_DSN=postgresql://postgres:postgres@127.0.0.1:55442/prod \
  DJANGO_ENV=~/.clama/railway-web.env python3 -I extrair_django.py /caminho/saida.json

O JSON sai no vocabulario do Django (status, flags, hash de senha como esta); as regras de mapeamento ficam no Java
(me.clama.virada), cobertas por teste. Imprime so contagens: nada de e-mail, nome, CPF ou texto.
Repasses: so os dois de teste conhecidos podem existir; qualquer outro para a extracao (decidir antes da virada).
"""
import hashlib
import hmac
import json
import os
import sys
from pathlib import Path

import psycopg
import psycopg.rows
from cryptography.fernet import Fernet, InvalidToken

REPASSES_DE_TESTE = {"56469489-51f0-47fc-a7d1-6571ab327924", "ac95a791-a51f-4ebe-bf2d-11c033b11e8b"}


def ler_env(caminho):
    valores = {}
    for linha in Path(caminho).expanduser().read_text().splitlines():
        linha = linha.strip()
        if not linha or linha.startswith("#") or "=" not in linha:
            continue
        k, v = linha.split("=", 1)
        valores[k.strip().removeprefix("export ").strip()] = v.strip().strip("'\"")
    return valores


def iso(t):
    return None if t is None else t.isoformat()


def main():
    if len(sys.argv) != 2:
        sys.exit("uso: extrair_django.py <saida.json>")
    saida = Path(sys.argv[1])
    env = ler_env(os.environ.get("DJANGO_ENV", "~/.clama/railway-web.env"))
    fernet = Fernet(env["FIELD_ENCRYPTION_KEY"])
    segredo_freemium = env.get("FREEMIUM_HASH_SECRET", "").encode()
    falhas = 0

    def dec(v):
        nonlocal falhas
        if v is None or v == "":
            return v
        try:
            return fernet.decrypt(v.encode()).decode()
        except InvalidToken:
            falhas += 1
            return None

    with psycopg.connect(os.environ["SRC_DSN"], autocommit=False) as c:
        c.read_only = True
        cur = c.cursor(row_factory=psycopg.rows.dict_row)

        cur.execute("""select id, email, password, nome_completo, name, nome_format_blog, is_superuser, is_staff,
		  is_clama_admin, is_active, force_change_password, date_joined, last_login from users_user order by id""")
        usuarios = [{**u, "date_joined": iso(u["date_joined"]), "last_login": iso(u["last_login"])} for u in cur.fetchall()]

        cur.execute("select id::text, nome, complexidade, ordem, valor_centavos, ativo from plans_plan order by ordem")
        planos = cur.fetchall()
        cur.execute("select id::text, nome, ordem, ativo from instituicoes_instituicao order by ordem")
        instituicoes = cur.fetchall()

        # pagamento confirmado: o evento PROCESSADO (MP: data.id; Asaas: payment.id) e a hora em que chegou
        cur.execute("""select pedido_id::text, provider, coalesce(payload->'data'->>'id', payload->'payment'->>'id') pagamento_id,
		  created_at from payments_webhookevento where status = 'PROCESSADO' and pedido_id is not null order by created_at""")
        pagamentos = {}
        for e in cur.fetchall():
            pagamentos.setdefault(e["pedido_id"], {"gateway": e["provider"], "pagamento_id": e["pagamento_id"],
                                                   "confirmado_em": iso(e["created_at"])})

        cur.execute("""select id::text, user_id, plano_id::text, instituicao_id::text, status, valor_centavos, eh_gratuito,
		  nome, email, cpf_cnpj, telefone, idade, sexo, pedido_oracao, oracao_gerada, consent_aceito_at, consent_ip::text,
		  consent_versao, retry_count, created_at, updated_at, oracao_email_sent_at, provider_payment_id, pix_qr_code,
		  pix_qr_code_base64 from orders_pedido order by created_at""")
        pedidos = []
        for p in cur.fetchall():
            for campo in ("nome", "email", "cpf_cnpj", "telefone", "pedido_oracao", "oracao_gerada"):
                p[campo] = dec(p[campo])
            if p["consent_ip"]:
                p["consent_ip"] = p["consent_ip"].split("/")[0]
            for campo in ("consent_aceito_at", "created_at", "updated_at", "oracao_email_sent_at"):
                p[campo] = iso(p[campo])
            p["pagamento"] = pagamentos.get(p["id"])
            pedidos.append(p)

        cur.execute("select id::text, status from instituicoes_repasse")
        repasses = cur.fetchall()
        cur.execute("select email_hash, cpf_hash from freemium_freemiumblacklist")
        blacklist = cur.fetchall()
        c.rollback()

    if falhas:
        sys.exit(f"ERRO: {falhas} campo(s) nao decifraram com a FIELD_ENCRYPTION_KEY; nada foi gravado")
    estranhos = [r for r in repasses if r["id"] not in REPASSES_DE_TESTE]
    if estranhos:
        sys.exit(f"ERRO: {len(estranhos)} repasse(s) fora dos 2 de teste conhecidos; decidir antes (mapa 4.2). Nada gravado")

    # conferencia: toda linha da blacklist do Django casa com algum pedido gratuito (o importador bloqueia a partir deles)
    def h(v):
        return hmac.new(segredo_freemium, v.encode(), hashlib.sha256).hexdigest()

    def norm_email(e):
        e = (e or "").strip().lower()
        if "@" not in e:
            return e
        local, dominio = e.split("@", 1)
        if dominio in ("gmail.com", "googlemail.com"):
            return local.split("+", 1)[0].replace(".", "") + "@gmail.com"
        return e

    emails = {h(norm_email(p["email"])) for p in pedidos if p["eh_gratuito"]}
    cpfs = {h("".join(ch for ch in (p["cpf_cnpj"] or "") if ch.isdigit())) for p in pedidos if p["eh_gratuito"]}
    cobertas = sum(1 for b in blacklist if b["email_hash"] in emails or b["cpf_hash"] in cpfs) if segredo_freemium else None

    doc = {
        "formato": "clama-django-v1",
        "notas": "PII EM CLARO. Apagar depois da importacao. Vocabulario do Django; o mapeamento esta em "
                 "clama-api me.clama.virada.ImportacaoDoDjango e no mapa da onda 8, par. 4.",
        "usuarios": usuarios,
        "planos": planos,
        "instituicoes": instituicoes,
        "pedidos": pedidos,
    }
    fd = os.open(saida, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, default=str)

    print(f"usuarios={len(usuarios)} pedidos={len(pedidos)} planos={len(planos)} instituicoes={len(instituicoes)}")
    print(f"pedidos com pagamento confirmado={sum(1 for p in pedidos if p['pagamento'])} "
          f"(mp={sum(1 for p in pedidos if p['pagamento'] and p['pagamento']['gateway'] == 'MERCADO_PAGO')})")
    print(f"repasses ignorados (teste)={len(repasses)} blacklist={len(blacklist)} cobertas por pedido gratuito={cobertas}")
    print(f"gravado em {saida} (modo 600)")


if __name__ == "__main__":
    main()
