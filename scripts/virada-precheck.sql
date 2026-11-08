-- Etapa 9 / janela de virada (mapa §6.0.3, §6.1.2 e §6.2.5): SO LEITURA. Rodar no D-1 e de novo em T0 (e no 6.2.5, depois de
-- desligar o Django) e guardar as saidas para comparar com o virada-poscheck.sql.
--
--   psql "$DATABASE_URL" -X -f scripts/virada-precheck.sql        # sslmode=require na URL, se o host exigir
--   (sem psql no Mac: docker run --rm -i postgres:18 psql "$DATABASE_URL" -X < scripts/virada-precheck.sql)
--
-- Nomes conferidos contra src/test/resources/db/schema-prod.sql e os models do Django (orders.Pedido, payments.WebhookEvento).
-- A transacao e READ ONLY: um erro de copia aqui nao escreve nada.
\set ON_ERROR_STOP on
SET default_transaction_read_only = on;
BEGIN READ ONLY;

\echo '== 1. Privilegios do usuario (Flyway precisa de CREATE no schema public; ddl-auto=validate so le)'
SELECT current_user AS usuario,
       current_database() AS banco,
       has_database_privilege(current_user, current_database(), 'CREATE') AS create_no_banco,
       has_schema_privilege('public', 'CREATE') AS create_no_schema_public,
       version() AS versao;

\echo '== 2. Conexoes (Django web + worker + Java somam; o Java usa pool pequeno)'
SHOW max_connections;
SELECT count(*) AS conexoes_agora FROM pg_stat_activity WHERE datname = current_database();

\echo '== 3. Pedidos por status (guardar para comparar com o poscheck)'
SELECT status, count(*) AS pedidos FROM orders_pedido GROUP BY status ORDER BY status;

\echo '== 3b. aguardando_pagamento nas ultimas 24 h (pagamento ainda pode chegar pelo webhook)'
SELECT count(*) AS aguardando_pagamento_24h
FROM orders_pedido
WHERE status = 'aguardando_pagamento' AND created_at > now() - interval '24 hours';

\echo '== 4. WebhookEvento em ERRO ou RECEBIDO (RECEBIDO parado = processamento que nao terminou)'
SELECT provider, status, count(*) AS eventos, min(created_at) AS mais_antigo
FROM payments_webhookevento
WHERE status IN ('ERRO', 'RECEBIDO')
GROUP BY provider, status ORDER BY provider, status;

\echo '== 5. Freemium em aguardando_confirmacao_email ha mais de 48 h (a reconciliacao os leva para erro)'
SELECT count(*) AS freemium_orfaos_48h
FROM orders_pedido
WHERE eh_gratuito AND status = 'aguardando_confirmacao_email' AND created_at < now() - interval '48 hours';

\echo '== 6. Tabelas do Java: as duas devem ser NULL antes da janela (poscheck: devem existir)'
SELECT to_regclass('public.scheduled_tasks') AS scheduled_tasks,
       to_regclass('public.flyway_schema_history') AS flyway_schema_history;

\echo '== 7. (§6.2.5) Pedidos para o "Reenviar" do 6.5.3: pago, gerando_oracao, oracao_gerada, aguardando_reenvio'
\echo '--    Rodar depois de desligar web e worker, com a fila do Redis drenada. So ids e datas, sem PII. (Se ja ha oracao nao da para ver aqui: oracao_gerada e cifrada, ate o vazio.)'
SELECT id, status, created_at, updated_at, retry_count, eh_gratuito
FROM orders_pedido
WHERE status IN ('pago', 'gerando_oracao', 'oracao_gerada', 'aguardando_reenvio')
ORDER BY status, created_at;

ROLLBACK;
