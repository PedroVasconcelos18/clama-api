-- Etapa 9 / janela de virada (mapa §6.3.3 e §6.5): SO LEITURA. Rodar depois que o Java subiu e de novo ao fim do 6.5.
--
--   psql "$DATABASE_URL" -X -f scripts/virada-poscheck.sql
--
-- Compare as secoes 3 e 4 com a saida do virada-precheck.sql: as contagens por status so podem ter andado pelos pedidos
-- novos e pelos reprocessados na janela.
\set ON_ERROR_STOP on
SET default_transaction_read_only = on;
BEGIN READ ONLY;

\echo '== 1. Tabelas do Java (as duas devem existir; antes da janela eram NULL)'
SELECT to_regclass('public.scheduled_tasks') AS scheduled_tasks,
       to_regclass('public.flyway_schema_history') AS flyway_schema_history;

\echo '== 2. Flyway (esperado: 0 BASELINE e 1 SQL "fila scheduled tasks", ambos success)'
SELECT installed_rank, version, description, type, success, installed_on
FROM flyway_schema_history ORDER BY installed_rank;

\echo '== 3. Pedidos por status (comparar com o precheck)'
SELECT status, count(*) AS pedidos FROM orders_pedido GROUP BY status ORDER BY status;

\echo '== 4. WebhookEvento em ERRO ou RECEBIDO (comparar com o precheck)'
SELECT provider, status, count(*) AS eventos, min(created_at) AS mais_antigo
FROM payments_webhookevento
WHERE status IN ('ERRO', 'RECEBIDO')
GROUP BY provider, status ORDER BY provider, status;

\echo '== 5. Jobs pendentes na fila do Java (a agenda cron nao fica aqui; picked=true: em execucao)'
SELECT task_name, count(*) AS linhas, count(*) FILTER (WHERE picked) AS em_execucao,
       count(*) FILTER (WHERE consecutive_failures > 0) AS com_falha, min(execution_time) AS proxima
FROM scheduled_tasks GROUP BY task_name ORDER BY task_name;

\echo '== 6. Auditoria do Java: ultimas 20 linhas do django_admin_log (acao 1 adicao, 2 mudanca, 3 exclusao)'
SELECT l.action_time, l.action_flag, ct.app_label || '.' || ct.model AS modelo, l.object_id, left(l.object_repr, 40) AS objeto,
       left(l.change_message, 60) AS mudanca, l.user_id
FROM django_admin_log l LEFT JOIN django_content_type ct ON ct.id = l.content_type_id
ORDER BY l.action_time DESC LIMIT 20;

\echo '== 6b. Linhas do django_admin_log na ultima hora'
SELECT count(*) AS na_ultima_hora FROM django_admin_log WHERE action_time > now() - interval '1 hour';

ROLLBACK;
