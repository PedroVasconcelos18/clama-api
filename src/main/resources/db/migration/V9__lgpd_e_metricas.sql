-- latencia pago -> entregue (D14): marcados uma vez cada, pelo proprio Pedido
ALTER TABLE pedido ADD COLUMN pago_em timestamptz, ADD COLUMN enviada_em timestamptz;
CREATE INDEX pedido_pago_em_ix ON pedido (pago_em) WHERE pago_em IS NOT NULL;
CREATE INDEX pedido_enviada_em_ix ON pedido (enviada_em) WHERE enviada_em IS NOT NULL;

-- uso da Claude por chamada bem-sucedida (D14). pedido_id sem FK: a exclusao LGPD apaga o pedido e a linha fica sem dono util.
CREATE TABLE oracao_geracao (
  id bigserial PRIMARY KEY,
  pedido_id uuid,
  criado_em timestamptz NOT NULL,
  modelo varchar(60) NOT NULL,
  tokens_entrada int NOT NULL,
  tokens_saida int NOT NULL,
  tokens_cache_escrita int NOT NULL DEFAULT 0,
  tokens_cache_leitura int NOT NULL DEFAULT 0,
  duracao_ms int NOT NULL
);
CREATE INDEX oracao_geracao_criado_ix ON oracao_geracao (criado_em);
