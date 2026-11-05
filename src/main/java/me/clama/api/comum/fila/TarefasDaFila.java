package me.clama.api.comum.fila;

import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import java.util.Map;

/** Holder: injetar Map direto faria o Spring coletar beans por nome. */
record TarefasDaFila(Map<String, OneTimeTask<?>> porNome) {}
