-- Seed sintético do e2e local (Etapa 9, tarefa 4; mapa §5.2). Roda depois do src/test/resources/db/schema-prod.sql
-- num Postgres vazio. Nenhum dado de produção, nenhuma PII real: domínio .test e senhas só deste arquivo.
--
--   verifica@clama.test / Senha-Verifica-123!   customer (o login do verifica-1-3.spec.ts)
--   admin@clama.test    / Senha-Admin-E2E-123!  admin (is_clama_admin, is_superuser)
--
-- password: argon2 do make_password do Django (o Java confere o mesmo formato).
-- email_hash: HMAC-SHA-256 do e-mail normalizado com FREEMIUM_HASH_SECRET=e2e (o scripts/e2e-local.sh usa esse
-- segredo nos dois backends). Trocar o segredo exige recalcular:
--   python3 -c "import hmac,hashlib; print(hmac.new(b'e2e', b'verifica@clama.test', hashlib.sha256).hexdigest())"
-- cpf_cnpj e telefone (cifrados) ficam nulos: o login não precisa deles. Sem pedidos.

BEGIN;

INSERT INTO users_user (password, is_superuser, email, is_staff, is_active, date_joined, name, is_clama_admin,
  nome_completo, force_change_password, email_hash, nome_format_blog, sexo)
VALUES
  ('argon2$argon2id$v=19$m=102400,t=2,p=8$SWlNVjdsT2ZQMDV3VDZWeG12NlFsRQ$vypIPXbsh9m3ErC3LhCCWamyAWAczbfB5WAE1DiEVcE',
   false, 'verifica@clama.test', false, true, now(), '', false, 'Verifica E2E', false,
   '34d256ae1868bc3ff9122b84fea7740ec3778e178e96ed749eb86b8c1eeadc97', 'completo', ''),
  ('argon2$argon2id$v=19$m=102400,t=2,p=8$SUQ5U29JdGhyTjhUUXFPRGxINlBGNA$nxuv4Ttxeio3DtJdd1IeqT/OT5wrT0DgkDHw/Jnmyvg',
   true, 'admin@clama.test', true, true, now(), '', true, 'Admin E2E', false,
   '837317198d1acfa37ecf8f82af8c5e4db3aaea871712aae3d6d61405d901a309', 'completo', '');

-- Livre (plans/0008) e o freemium (plans/0007): invisíveis; mais um plano visível para o card da landing
INSERT INTO plans_plan (created_at, updated_at, id, nome, valor_centavos, descricao, complexidade, ordem, ativo, visivel)
VALUES
  (now(), now(), 'e2e00000-0000-4000-8000-000000000001', 'Livre', 100, 'Oração de estrutura simples — valor livre',
   'simples', 0, true, false),
  (now(), now(), 'e2e00000-0000-4000-8000-000000000002', 'Gratuita', 1, 'Oração gratuita (freemium)',
   'simples_gratuita', 0, true, false),
  (now(), now(), 'e2e00000-0000-4000-8000-000000000003', 'Oração com versículo', 2000, 'Plano visível do e2e',
   'com_versiculo', 1, true, true);

INSERT INTO instituicoes_instituicao (created_at, updated_at, id, nome, ativo, ordem, logo)
VALUES (now(), now(), 'e2e00000-0000-4000-8000-000000000011', 'Instituição E2E', true, 0, '');

INSERT INTO prompts_prompttemplate (created_at, updated_at, id, nome, versao, system_prompt,
  instrucoes_por_complexidade, ativo)
VALUES (now(), now(), 'e2e00000-0000-4000-8000-000000000021', 'Prompt E2E', 1, 'Você escreve orações curtas.',
  '{"simples": "curta", "com_versiculo": "com um versículo", "com_profecia_e_versiculos": "com profecia",
    "simples_gratuita": "curta"}', true);

COMMIT;
