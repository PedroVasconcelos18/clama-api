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

-- Resumo do relatorio de repasse gerado pela tarefa do dia 28 (ou regerado): so os totais. O CSV tem nome de doador e sai
-- na hora, do ledger, a cada download (D5): guardado aqui seria PII fora da cifra.
create table relatorio_repasse (
  id bigserial primary key,
  instituicao_id bigint not null references instituicao (id),
  de date not null,
  ate date not null,
  gerado_em timestamptz not null,
  quantidade int not null check (quantidade >= 0),
  total_doado_centavos bigint not null,
  total_repasse_centavos bigint not null,
  pendente_centavos bigint not null,
  check (ate >= de),
  unique (instituicao_id, de, ate)
);
