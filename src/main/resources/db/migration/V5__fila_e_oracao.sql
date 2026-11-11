-- editavel ate o primeiro deploy

-- Fila e tarefas recorrentes (D13): DDL oficial do db-scheduler 17.0.0 (postgresql_tables.sql), sem mudanca
create table scheduled_tasks (
  task_name text not null,
  task_instance text not null,
  task_data bytea,
  execution_time timestamp with time zone not null,
  picked BOOLEAN not null,
  picked_by text,
  last_success timestamp with time zone,
  last_failure timestamp with time zone,
  consecutive_failures INT,
  last_heartbeat timestamp with time zone,
  version BIGINT not null,
  priority SMALLINT,
  PRIMARY KEY (task_name, task_instance)
);

CREATE INDEX execution_time_idx ON scheduled_tasks (execution_time);
CREATE INDEX last_heartbeat_idx ON scheduled_tasks (last_heartbeat);
CREATE INDEX priority_execution_time_idx on scheduled_tasks (priority desc, execution_time asc);

-- Prompts (D15): cada gravacao no admin e uma versao nova e imutavel (conteudo nunca recebe UPDATE). No maximo uma
-- ativa (indice parcial unico); sem ativa vale o padrao do codigo (resources/oracao/prompt-padrao.yml).
-- conteudo: {sistema, simples, comVersiculo, comProfeciaEVersiculos, gratuito}. criado_por sem FK, como a auditoria.
create table prompt_versao (
  id bigserial primary key,
  conteudo jsonb not null,
  criado_em timestamptz not null,
  criado_por bigint,
  ativo boolean not null default false
);

create unique index prompt_versao_uma_ativa on prompt_versao (ativo) where ativo;

-- o banco garante a imutabilidade: UPDATE que muda o conteudo falha (ativo continua mudando)
create function prompt_versao_imutavel() returns trigger language plpgsql as $$
begin
  raise exception 'prompt_versao.conteudo e imutavel: grave uma versao nova';
end $$;

create trigger prompt_versao_imutavel before update of conteudo on prompt_versao
  for each row when (old.conteudo is distinct from new.conteudo) execute function prompt_versao_imutavel();

-- Documentos de contexto (D14): a Files API da Anthropic e a fonte da verdade (o arquivo nao fica em disco nosso); aqui
-- so os metadados. mime, tamanho_bytes e file_id sao nulos ate o upload; ativo exige file_id.
create table documento_contexto (
  id bigserial primary key,
  nome text not null,
  mime text,
  tamanho_bytes bigint,
  file_id text,
  ativo boolean not null default false,
  criado_em timestamptz not null,
  atualizado_em timestamptz not null,
  constraint documento_contexto_ativo_com_arquivo check (not ativo or file_id is not null)
);
