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
