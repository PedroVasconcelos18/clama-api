/**
 * LGPD do titular: busca pelo e-mail, exportacao em JSON, exclusao numa transacao e "apagar so o blog". Sem SQL
 * proprio: tudo passa pelas APIs dos outros modulos. Ninguem depende dele.
 */
@ApplicationModule(allowedDependencies = { "conta", "pedido", "pagamento", "blog", "freemium", "infra" })
package me.clama.lgpd;

import org.springframework.modulith.ApplicationModule;
