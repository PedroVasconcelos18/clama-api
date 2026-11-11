-- editavel ate o primeiro deploy
-- PII (nome, email, cpf_cnpj, telefone, pedido_oracao, oracao) em AES-256-GCM ("v1:..."), ver infra/cripto; busca por email_hmac/cpf_hmac
CREATE TABLE pedido (
	id uuid PRIMARY KEY,
	usuario_id bigint REFERENCES usuario (id) ON DELETE SET NULL,
	plano_id bigint NOT NULL REFERENCES plano (id),
	instituicao_id bigint REFERENCES instituicao (id),
	status varchar(30) NOT NULL CHECK (status IN ('AGUARDANDO_PAGAMENTO', 'PAGO', 'GERANDO_ORACAO', 'ORACAO_GERADA',
		'ENVIADA', 'AGUARDANDO_NOVA_TENTATIVA', 'ERRO')),
	valor_centavos bigint NOT NULL CHECK (valor_centavos >= 0),
	gratuito boolean NOT NULL DEFAULT false,
	nome text NOT NULL,
	email text NOT NULL,
	email_hmac varchar(64) NOT NULL,
	cpf_cnpj text,
	cpf_hmac varchar(64),
	telefone text,
	idade smallint,
	sexo varchar(20),
	pedido_oracao text,
	oracao text,
	consentido_em timestamptz NOT NULL,
	consentimento_ip varchar(45),
	ultimo_erro text,
	tentativas int NOT NULL DEFAULT 0 CHECK (tentativas >= 0),
	criado_em timestamptz NOT NULL,
	atualizado_em timestamptz NOT NULL,
	CHECK (NOT gratuito OR valor_centavos = 0)
);
CREATE INDEX pedido_email_hmac_ix ON pedido (email_hmac);
CREATE INDEX pedido_cpf_hmac_ix ON pedido (cpf_hmac) WHERE cpf_hmac IS NOT NULL;
CREATE INDEX pedido_usuario_ix ON pedido (usuario_id) WHERE usuario_id IS NOT NULL;
CREATE INDEX pedido_status_atualizado_ix ON pedido (status, atualizado_em);
