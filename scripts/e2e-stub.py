"""Serviços externos de mentira para o e2e local (scripts/e2e-local.sh). Só stdlib; ouve em 127.0.0.1.

Os dois backends apontam para cá pela própria config (nenhum mock de perfil no código):
- Claude:    POST /v1/messages              -> Message com uma oração fixa
- Resend:    POST /emails                   -> guarda o e-mail na caixa de saída (memória)
- Turnstile: POST /turnstile/v0/siteverify  -> {"success": true}
- Caixa:     GET  /outbox                   -> JSON com os e-mails recebidos (o spec do freemium lê o link daqui)

Uso: python3 scripts/e2e-stub.py <porta>
"""

import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CAIXA = []

ORACAO = {
    "id": "msg_e2e", "type": "message", "role": "assistant", "model": "claude-e2e",
    "content": [{"type": "text", "text": "Senhor, abençoa este pedido. Amém. (oração do e2e)"}],
    "stop_reason": "end_turn", "stop_sequence": None, "usage": {"input_tokens": 1, "output_tokens": 1},
}


class Stub(BaseHTTPRequestHandler):
    def _json(self, codigo, corpo):
        dados = json.dumps(corpo).encode()
        self.send_response(codigo)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(dados)))
        self.end_headers()
        self.wfile.write(dados)

    def do_POST(self):
        corpo = self.rfile.read(int(self.headers.get("Content-Length") or 0))
        caminho = self.path.split("?")[0].rstrip("/")
        if caminho.endswith("/v1/messages"):
            self._json(200, ORACAO)
        elif caminho.endswith("/emails"):
            email = json.loads(corpo)
            CAIXA.append(email)
            print(f"stub: e-mail para {email.get('to')}: {email.get('subject')}", flush=True)
            self._json(200, {"id": f"e2e-{len(CAIXA)}"})
        elif caminho.endswith("/siteverify"):
            self._json(200, {"success": True})
        else:
            self._json(404, {"erro": f"stub nao conhece {self.path}"})

    def do_GET(self):
        if self.path.split("?")[0].rstrip("/") == "/outbox":
            self._json(200, CAIXA)
        else:
            self._json(404, {"erro": f"stub nao conhece {self.path}"})

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), Stub).serve_forever()
