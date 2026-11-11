-- editavel ate o primeiro deploy
-- pedido freemium: dois status novos (o CHECK de coluna da V3 se chama pedido_status_check) e o indice dos pendentes
ALTER TABLE pedido DROP CONSTRAINT pedido_status_check;
ALTER TABLE pedido ADD CONSTRAINT pedido_status_check CHECK (status IN ('AGUARDANDO_PAGAMENTO', 'PAGO',
  'GERANDO_ORACAO', 'ORACAO_GERADA', 'ENVIADA', 'AGUARDANDO_NOVA_TENTATIVA', 'ERRO',
  'AGUARDANDO_CONFIRMACAO_EMAIL', 'CONFIRMACAO_EXPIRADA'));
CREATE INDEX pedido_pendente_freemium_ix ON pedido (criado_em) WHERE status = 'AGUARDANDO_CONFIRMACAO_EMAIL';
