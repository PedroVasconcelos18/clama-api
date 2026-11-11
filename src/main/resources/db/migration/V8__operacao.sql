-- editavel ate o primeiro deploy

-- Uma linha por execucao de tarefa recorrente (agendada ou "Rodar agora"): e o "rodou ou nao?" que o beat nunca deu (D9).
-- Gravada no fim; quem esta rodando agora aparece em scheduled_tasks.picked. Sem FK no autor, como a auditoria.
create table tarefa_execucao (
  id bigserial primary key,
  tarefa varchar(60) not null,
  inicio timestamptz not null,
  fim timestamptz not null,
  simulacao boolean not null,
  manual boolean not null,
  autor_id bigint,
  ok boolean not null,
  resumo varchar(500), -- uma linha devolvida pela tarefa, sem PII
  erro varchar(200),   -- so a classe da excecao; o detalhe vai ao Sentry
  check (fim >= inicio)
);
create index tarefa_execucao_ix on tarefa_execucao (tarefa, inicio desc);
