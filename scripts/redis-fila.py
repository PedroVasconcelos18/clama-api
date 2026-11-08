#!/usr/bin/env python3
"""Etapa 9 / janela (mapa 6.2.2): tamanho da fila do Celery no Redis. SO LEITURA (AUTH, SELECT, LLEN, HLEN).

  CELERY_BROKER_URL=redis://default:senha@host:6379/0 python3 scripts/redis-fila.py
  railway run --service worker -- python3 scripts/redis-fila.py     (REDIS_URL serve se CELERY_BROKER_URL faltar)
  CELERY_BROKER_URL e o broker real do Celery (config/settings/base.py); REDIS_URL em producao e o cache do Django.

Socket cru em vez do redis-py: o redis-py trava contra o proxy publico do Railway (conexao meio-aberta). rediss:// liga TLS.
Saida: `<variavel usada> db=<n> celery=<LLEN celery> unacked=<HLEN unacked>` (sem host nem senha). Pronto para desligar o worker quando os dois forem 0.
Exit 0 se leu, 1 em qualquer erro (URL, rede, AUTH, resposta inesperada). Nunca escreve no Redis.
"""
import os
import socket
import ssl
import sys
from urllib.parse import unquote, urlparse

TIMEOUT = 10


def comando(*partes):
    return b"".join([b"*%d\r\n" % len(partes)] + [b"$%d\r\n%s\r\n" % (len(p), p) for p in map(str.encode, partes)])


def resposta(f):
    linha = f.readline()
    if not linha.endswith(b"\r\n"):
        raise RuntimeError("conexao fechada antes da resposta")
    tipo, resto = linha[:1], linha[1:-2]
    if tipo == b"-":
        raise RuntimeError("redis: " + resto.decode(errors="replace"))
    if tipo in (b"+", b":"):
        return resto
    raise RuntimeError("resposta inesperada: %r" % linha[:40])


def main():
    nome = next((n for n in ("CELERY_BROKER_URL", "REDIS_URL") if os.environ.get(n)), None)
    if not nome:
        raise RuntimeError("defina CELERY_BROKER_URL (ou REDIS_URL)")
    url = os.environ[nome]
    u = urlparse(url)
    if u.scheme not in ("redis", "rediss") or not u.hostname:
        raise RuntimeError("URL deve ser redis:// ou rediss:// com host")
    s = socket.create_connection((u.hostname, u.port or 6379), TIMEOUT)
    s.settimeout(TIMEOUT)
    if u.scheme == "rediss":
        s = ssl.create_default_context().wrap_socket(s, server_hostname=u.hostname)
    with s, s.makefile("rb") as f:
        def chamar(*partes):
            s.sendall(comando(*partes))
            return resposta(f)

        if u.password:
            chamar("AUTH", *( [unquote(u.username)] if u.username else []), unquote(u.password))
        if u.path.strip("/"):
            chamar("SELECT", u.path.strip("/"))
        celery = int(chamar("LLEN", "celery"))
        unacked = int(chamar("HLEN", "unacked"))
    print("%s db=%s celery=%d unacked=%d" % (nome, u.path.strip("/") or "0", celery, unacked))


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, ValueError) as e:
        print("ERRO: %s" % e, file=sys.stderr)
        sys.exit(1)
