-- editavel ate o primeiro deploy
CREATE TABLE plano (
	id bigserial PRIMARY KEY,
	nome varchar(80) NOT NULL,
	descricao text NOT NULL DEFAULT '',
	valor_centavos bigint NOT NULL CHECK (valor_centavos >= 0),
	complexidade varchar(30) NOT NULL CHECK (complexidade IN ('SIMPLES', 'COM_VERSICULO', 'COM_PROFECIA_E_VERSICULOS')),
	tipo varchar(20) NOT NULL DEFAULT 'COMUM' CHECK (tipo IN ('COMUM', 'GRATUITO', 'VALOR_LIVRE')),
	ordem int NOT NULL DEFAULT 0 CHECK (ordem >= 0),
	ativo boolean NOT NULL DEFAULT true,
	CHECK (tipo = 'COMUM' OR ativo)
);
CREATE UNIQUE INDEX plano_nome_ativo_ux ON plano (nome) WHERE ativo;
CREATE UNIQUE INDEX plano_tipo_tecnico_ux ON plano (tipo) WHERE tipo <> 'COMUM';

CREATE TABLE instituicao (
	id bigserial PRIMARY KEY,
	nome varchar(120) NOT NULL,
	ordem int NOT NULL DEFAULT 0 CHECK (ordem >= 0),
	ativo boolean NOT NULL DEFAULT true
);
CREATE UNIQUE INDEX instituicao_nome_ativo_ux ON instituicao (nome) WHERE ativo;

-- fora de instituicao para a lista e o admin nao carregarem blobs
CREATE TABLE instituicao_logo (
	instituicao_id bigint PRIMARY KEY REFERENCES instituicao (id) ON DELETE CASCADE,
	conteudo bytea NOT NULL,
	mime varchar(30) NOT NULL,
	sha256 varchar(64) NOT NULL,
	atualizado_em timestamptz NOT NULL
);

-- planos tecnicos: o app quebra sem eles (freemium 503, "nenhum plano disponivel"); ordem alta para nao disputar com os comuns
INSERT INTO plano (nome, descricao, valor_centavos, complexidade, tipo, ordem)
VALUES ('Gratuito', 'Sua primeira oração — por nossa conta', 0, 'SIMPLES', 'GRATUITO', 1000),
       ('Livre', '', 1, 'SIMPLES', 'VALOR_LIVRE', 1000)
ON CONFLICT (tipo) WHERE tipo <> 'COMUM' DO NOTHING;
