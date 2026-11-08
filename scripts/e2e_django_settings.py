"""Settings do Django para o e2e local (scripts/e2e-local.sh, BACKEND=django): config.settings.local sem Redis.

- cache em memória e Celery eager: as tasks (e-mail, oração) rodam no próprio runserver, sem worker nem broker;
- e-mail pela Resend do Anymail apontada para o stub (scripts/e2e-stub.py), a mesma caixa de saída do Java.
Nunca usar fora do e2e local: o runner monta o DATABASE_URL de um Postgres em localhost.
"""

import os

from config.settings.local import *  # noqa: F403

CACHES = {"default": {"BACKEND": "django.core.cache.backends.locmem.LocMemCache"}}
CELERY_TASK_ALWAYS_EAGER = True
EMAIL_BACKEND = "anymail.backends.resend.EmailBackend"
ANYMAIL = {"RESEND_API_KEY": "re_e2e", "RESEND_API_URL": os.environ["E2E_STUB_URL"]}
