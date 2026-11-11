-- editavel ate o primeiro deploy

-- Cobrancas Pix do pedido, todas guardadas (D16, D8: a T5 reconhece qualquer pagamento do pedido e acha o orfao).
-- Vigente: a de maior n, nao cancelada e nao vencida. A seguinte usa n + 1 e a chave "clama-pedido-<id>-<n>" (a 1a sem sufixo)
CREATE TABLE cobranca (
	id bigserial PRIMARY KEY,
	pedido_id uuid NOT NULL REFERENCES pedido (id) ON DELETE CASCADE,
	n int NOT NULL CHECK (n >= 1),
	mp_pagamento_id bigint NOT NULL UNIQUE,
	pix_copia_e_cola text NOT NULL,
	pix_qr_base64 text,
	expira_em timestamptz NOT NULL,
	criada_em timestamptz NOT NULL,
	cancelada_em timestamptz,
	-- a cobranca que pagou o pedido (no maximo uma por pedido): separa reentrega de pagamento orfao e diz de quem e o estorno
	paga_em timestamptz,
	UNIQUE (pedido_id, n)
);

-- Notificacoes do Mercado Pago (D21: so MP, sem provider). O estado vem sempre do GET do pagamento, nunca do corpo.
-- pedido_id sem FK: o external_reference pode apontar para pedido inexistente (vira IGNORADO e fica registrado)
CREATE TABLE pagamento_evento (
	id bigserial PRIMARY KEY,
	id_externo varchar(64) NOT NULL UNIQUE,
	tipo varchar(60),
	mp_pagamento_id varchar(64),
	payload jsonb NOT NULL,
	status varchar(20) NOT NULL CHECK (status IN ('RECEBIDO', 'PROCESSADO', 'IGNORADO', 'ERRO')),
	-- codigo curto (valor_divergente, pagamento_orfao, nao_aprovado...) ou erro resumido, sem PII
	motivo varchar(200),
	pedido_id uuid,
	recebido_em timestamptz NOT NULL,
	atualizado_em timestamptz NOT NULL
);
CREATE INDEX pagamento_evento_pedido_ix ON pagamento_evento (pedido_id) WHERE pedido_id IS NOT NULL;
CREATE INDEX pagamento_evento_status_ix ON pagamento_evento (status, recebido_em);

-- Repasse a instituicao (D9): nasce REGISTRADO no pagamento; estorno antes da transferencia o CANCELA.
-- percentual e valor_base sao fotografia do momento (mudar a configuracao nao reescreve o passado)
CREATE TABLE repasse (
	id bigserial PRIMARY KEY,
	pedido_id uuid NOT NULL UNIQUE REFERENCES pedido (id),
	instituicao_id bigint NOT NULL REFERENCES instituicao (id),
	valor_base_centavos bigint NOT NULL CHECK (valor_base_centavos > 0),
	percentual smallint NOT NULL CHECK (percentual BETWEEN 0 AND 100),
	valor_centavos bigint NOT NULL CHECK (valor_centavos >= 0),
	status varchar(20) NOT NULL CHECK (status IN ('REGISTRADO', 'TRANSFERIDO', 'CANCELADO')),
	criado_em timestamptz NOT NULL,
	transferido_em timestamptz,
	cancelado_em timestamptz,
	motivo varchar(200),
	comprovante varchar(120),
	CHECK (status <> 'TRANSFERIDO' OR comprovante IS NOT NULL),
	CHECK ((status = 'TRANSFERIDO') = (transferido_em IS NOT NULL)),
	CHECK ((status = 'CANCELADO') = (cancelado_em IS NOT NULL))
);
CREATE UNIQUE INDEX cobranca_paga_ux ON cobranca (pedido_id) WHERE paga_em IS NOT NULL;
CREATE INDEX repasse_instituicao_status_ix ON repasse (instituicao_id, status);
