/** Blog: espelho dos posts do WordPress, comentarios, curtidas e banimento. Depende de conta e infra; ninguem depende dele. */
@ApplicationModule(allowedDependencies = { "conta", "infra" })
package me.clama.blog;

import org.springframework.modulith.ApplicationModule;
