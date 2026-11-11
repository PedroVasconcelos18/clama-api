-- editavel ate o primeiro deploy
-- pedido freemium: dois status novos (o CHECK de coluna da V3 se chama pedido_status_check) e o indice dos pendentes
ALTER TABLE pedido DROP CONSTRAINT pedido_status_check;
ALTER TABLE pedido ADD CONSTRAINT pedido_status_check CHECK (status IN ('AGUARDANDO_PAGAMENTO', 'PAGO',
  'GERANDO_ORACAO', 'ORACAO_GERADA', 'ENVIADA', 'AGUARDANDO_NOVA_TENTATIVA', 'ERRO',
  'AGUARDANDO_CONFIRMACAO_EMAIL', 'CONFIRMACAO_EXPIRADA'));
CREATE INDEX pedido_pendente_freemium_ix ON pedido (criado_em) WHERE status = 'AGUARDANDO_CONFIRMACAO_EMAIL';

-- link de confirmacao: so o SHA-256 do token; varios por pedido (cada envio do job emite um)
CREATE TABLE freemium_confirmacao (
  id bigserial PRIMARY KEY,
  pedido_id uuid NOT NULL REFERENCES pedido (id) ON DELETE CASCADE,
  hash char(64) NOT NULL UNIQUE,
  criado_em timestamptz NOT NULL,
  expira_em timestamptz NOT NULL,
  usado_em timestamptz
);
CREATE INDEX freemium_confirmacao_pedido_ix ON freemium_confirmacao (pedido_id);

-- quem ja usou a oracao gratuita; sobrevive a exclusao da conta e do pedido (sem FK, INV:52, INV:163)
CREATE TABLE freemium_bloqueio (
  id bigserial PRIMARY KEY,
  email_hmac varchar(64) NOT NULL UNIQUE,
  cpf_hmac varchar(64) NOT NULL UNIQUE,
  ip_hmac varchar(64),
  criado_em timestamptz NOT NULL
);
CREATE INDEX freemium_bloqueio_ip_ix ON freemium_bloqueio (ip_hmac, criado_em) WHERE ip_hmac IS NOT NULL;
