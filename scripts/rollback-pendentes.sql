-- Etapa 9 / rollback da janela (mapa §6.7.2 e §6.7.5): SO LEITURA. NAO EXECUTA NADA: imprime a lista dos jobs do Java que
-- ficaram pendentes em scheduled_tasks e as LINHAS DE COMANDO prontas para o Django reprocessar (colar no terminal, de proposito
-- a mao). Rodar so DEPOIS de `railway scale --service <java> RW=0` (a drenagem de 60 s deixa o que estava em curso terminar).
--
--   psql "$DATABASE_URL" -X -f scripts/rollback-pendentes.sql
--
-- task_name = TipoDeJob.nome(); task_instance = "<nome>/<id>" (id do pedido, do user para boas-vindas, do evento para
-- sincronizar-espelho). A agenda (pedidos-presos, reconciliar-*, ...) NAO esta nesta tabela: roda no scheduler do Spring.
-- Se der "relation scheduled_tasks does not exist", o Flyway nunca rodou: nao ha nada pendente do Java.
-- Nao e testado contra producao: o Django ignora scheduled_tasks, por isso os pendentes do Java precisam ser reenfileirados.
-- Tasks do Celery conferidas em clama-backend (shared_task): gerar_oracao_task, enviar_oracao_task,
-- enviar_email_confirmacao_freemium_task(pedido_id, token), enviar_email_boas_vindas_doador_task(user_id), enviar_alerta_admin_task.
\set ON_ERROR_STOP on
SET default_transaction_read_only = on;
BEGIN READ ONLY;

\echo '== 1. Tudo o que esta na fila (picked=true: em execucao quando o Java parou; falhas > 0: ja tentou e vai de novo)'
SELECT task_name, task_instance, execution_time, picked, consecutive_failures AS falhas
FROM scheduled_tasks ORDER BY execution_time;

\echo '== 2. Por tipo (task_name nao mapeado abaixo exige decisao do Pedro: ver o que o job fazia)'
SELECT task_name, count(*) AS linhas FROM scheduled_tasks GROUP BY task_name ORDER BY task_name;

\echo '== 3. Pedidos com job pendente: id, status do pedido e o job'
SELECT t.task_name, p.id AS pedido, p.status, p.retry_count, t.execution_time
FROM scheduled_tasks t
JOIN orders_pedido p ON p.id::text = split_part(t.task_instance, '/', 2)
WHERE t.task_name IN ('gerar-oracao', 'enviar-oracao', 'reenviar-oracao', 'alerta-admin', 'confirmacao-freemium')
ORDER BY t.task_name, t.execution_time;

\echo '== 3b. Jobs de pedido cujo id nao existe em orders_pedido (deveria ser vazio)'
SELECT t.task_name, t.task_instance
FROM scheduled_tasks t
WHERE t.task_name IN ('gerar-oracao', 'enviar-oracao', 'reenviar-oracao', 'alerta-admin', 'confirmacao-freemium')
  AND NOT EXISTS (SELECT 1 FROM orders_pedido p WHERE p.id::text = split_part(t.task_instance, '/', 2));

\echo '== 4. Sem equivalente no Django (nao reenfileirar; so registrar): sincronizar-espelho'
SELECT task_name, task_instance, execution_time FROM scheduled_tasks WHERE task_name = 'sincronizar-espelho' ORDER BY execution_time;

\echo '== 5. Linhas de comando (so as que tem ids; uma por task). Usar depois do `railway scale --service web RW=1` e do worker.'
\pset format unaligned
\pset tuples_only on

-- gerar_oracao_task: gerar-oracao
SELECT 'railway run --service web -- python manage.py shell -c "from clama.prayer_generation.tasks import gerar_oracao_task as t; [t.delay(i) for i in ['
       || string_agg('''' || split_part(task_instance, '/', 2) || '''', ', ' ORDER BY execution_time) || ']]"'
FROM scheduled_tasks WHERE task_name = 'gerar-oracao' HAVING count(*) > 0;

-- enviar_oracao_task: enviar-oracao e reenviar-oracao (o envio e por pedido; o Django nao tem o "envio" do Java)
SELECT 'railway run --service web -- python manage.py shell -c "from clama.notifications.tasks import enviar_oracao_task as t; [t.delay(i) for i in ['
       || string_agg(DISTINCT '''' || split_part(task_instance, '/', 2) || '''', ', ') || ']]"'
FROM scheduled_tasks WHERE task_name IN ('enviar-oracao', 'reenviar-oracao') HAVING count(*) > 0;

-- enviar_alerta_admin_task: alerta-admin
SELECT 'railway run --service web -- python manage.py shell -c "from clama.notifications.tasks import enviar_alerta_admin_task as t; [t.delay(i) for i in ['
       || string_agg('''' || split_part(task_instance, '/', 2) || '''', ', ' ORDER BY execution_time) || ']]"'
FROM scheduled_tasks WHERE task_name = 'alerta-admin' HAVING count(*) > 0;

-- enviar_email_boas_vindas_doador_task: boas-vindas (user_id; a task recebe str)
SELECT 'railway run --service web -- python manage.py shell -c "from clama.notifications.tasks import enviar_email_boas_vindas_doador_task as t; [t.delay(i) for i in ['
       || string_agg('''' || split_part(task_instance, '/', 2) || '''', ', ' ORDER BY execution_time) || ']]"'
FROM scheduled_tasks WHERE task_name = 'boas-vindas' HAVING count(*) > 0;

-- enviar_email_confirmacao_freemium_task(pedido_id, token): confirmacao-freemium. O token e o da tabela (o job do Java o
-- busca pelo pedido): o mais recente, nao usado e nao vencido. Pedido sem token valido nao entra (ver a secao 6).
-- ATENCAO: a linha impressa leva o token de confirmacao (credencial de uso unico); nao colar em canal compartilhado.
SELECT 'railway run --service web -- python manage.py shell -c "from clama.notifications.tasks import enviar_email_confirmacao_freemium_task as t; [t.delay(p, k) for p, k in ['
       || string_agg('(''' || x.pedido || ''', ''' || x.token || ''')', ', ') || ']]"'
FROM (
    SELECT DISTINCT ON (split_part(t.task_instance, '/', 2)) split_part(t.task_instance, '/', 2) AS pedido, k.token
    FROM scheduled_tasks t
    JOIN freemium_freemiumconfirmationtoken k ON k.pedido_id::text = split_part(t.task_instance, '/', 2)
    WHERE t.task_name = 'confirmacao-freemium' AND k.used_at IS NULL AND k.expires_at > now()
    ORDER BY split_part(t.task_instance, '/', 2), k.created_at DESC
) x HAVING count(*) > 0;

\pset tuples_only off
\pset format aligned

\echo '== 6. confirmacao-freemium SEM token valido (nao ha linha de comando; o pedido fica para a reconciliacao do Django)'
SELECT t.task_instance
FROM scheduled_tasks t
WHERE t.task_name = 'confirmacao-freemium'
  AND NOT EXISTS (SELECT 1 FROM freemium_freemiumconfirmationtoken k
                  WHERE k.pedido_id::text = split_part(t.task_instance, '/', 2) AND k.used_at IS NULL AND k.expires_at > now());

ROLLBACK;
