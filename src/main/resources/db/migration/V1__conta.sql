-- editavel ate o primeiro deploy
CREATE TABLE usuario (
	id bigserial PRIMARY KEY,
	email varchar(254) NOT NULL UNIQUE CHECK (email = lower(email)),
	email_canonico varchar(254) NOT NULL UNIQUE,
	senha varchar(255),
	nome_completo varchar(120) NOT NULL DEFAULT '',
	nome_formato_blog varchar(20) NOT NULL DEFAULT 'compacto' CHECK (nome_formato_blog IN ('compacto', 'completo')),
	papel varchar(20) NOT NULL CHECK (papel IN ('CLIENTE', 'ADMIN', 'SUPERUSUARIO')),
	ativo boolean NOT NULL DEFAULT true,
	criado_em timestamptz NOT NULL,
	ultimo_login_em timestamptz
);

CREATE TABLE token_de_senha (
	id bigserial PRIMARY KEY,
	usuario_id bigint NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
	hash char(64) NOT NULL UNIQUE,
	finalidade varchar(20) NOT NULL CHECK (finalidade IN ('REDEFINIR', 'DEFINIR')),
	criado_em timestamptz NOT NULL,
	expira_em timestamptz NOT NULL,
	usado_em timestamptz
);
CREATE INDEX token_de_senha_usuario_ix ON token_de_senha (usuario_id);

CREATE TABLE auditoria (
	id bigserial PRIMARY KEY,
	ocorrido_em timestamptz NOT NULL,
	autor_id bigint,
	entidade varchar(60) NOT NULL,
	entidade_id varchar(60) NOT NULL,
	acao varchar(20) NOT NULL CHECK (acao IN ('ADICAO', 'MUDANCA', 'EXCLUSAO', 'RESGATE')),
	diff jsonb NOT NULL DEFAULT '{}'
);
CREATE INDEX auditoria_entidade_ix ON auditoria (entidade, entidade_id);

-- Spring Session JDBC (schema-postgresql.sql do spring-session-jdbc 4.1.1)
CREATE TABLE SPRING_SESSION (
	PRIMARY_ID CHAR(36) NOT NULL,
	SESSION_ID CHAR(36) NOT NULL,
	CREATION_TIME BIGINT NOT NULL,
	LAST_ACCESS_TIME BIGINT NOT NULL,
	MAX_INACTIVE_INTERVAL INT NOT NULL,
	EXPIRY_TIME BIGINT NOT NULL,
	PRINCIPAL_NAME VARCHAR(100),
	CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
	SESSION_PRIMARY_ID CHAR(36) NOT NULL,
	ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
	ATTRIBUTE_BYTES BYTEA NOT NULL,
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
);
