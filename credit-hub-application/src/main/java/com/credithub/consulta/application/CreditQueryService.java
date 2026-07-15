package com.credithub.consulta.application;

import com.credithub.consulta.application.port.CreditBureauPort;
import com.credithub.consulta.domain.Bureau;
import com.credithub.consulta.domain.ConsultaConsolidada;
import com.credithub.consulta.domain.CreditReport;

import java.time.Duration;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Scatter-gather: dispara todos os bureaus em paralelo (1 virtual thread cada) sob um deadline global.
 * Quem não terminar até o deadline é cancelado e entra em "indisponiveis"; a confiança sai da contagem.
 * A resiliência de cada perna (bulkhead/breaker/retry) fica no adapter; aqui só orquestra e agrega.
 *
 * O executor por-request vem injetado (Supplier<ExecutorService>) em vez de criado aqui: assim o
 * bootstrap pode fornecer um executor context-aware (propaga o trace-context às virtual threads) sem
 * arrastar Micrometer para este módulo — que permanece framework-free (só java.util.concurrent).
 */
public class CreditQueryService {

    private final List<CreditBureauPort> bureaus;
    private final Duration deadline;
    private final Supplier<ExecutorService> executorFactory;

    public CreditQueryService(List<CreditBureauPort> bureaus, Duration deadline,
                              Supplier<ExecutorService> executorFactory) {
        this.bureaus = List.copyOf(bureaus);
        this.deadline = deadline;
        this.executorFactory = executorFactory;
    }

    public ConsultaConsolidada consultar(String cpf) {
        List<Callable<CreditReport>> tasks = bureaus.stream()
                .map(b -> (Callable<CreditReport>) () -> b.consultar(cpf))
                .toList();

        try (var executor = executorFactory.get()) {
            // invokeAll com timeout = deadline global: retorna quando todos terminam OU o tempo estoura;
            // as tasks não concluídas são canceladas (o future.get() lança CancellationException).
            List<Future<CreditReport>> results =
                    executor.invokeAll(tasks, deadline.toMillis(), TimeUnit.MILLISECONDS);

            Map<Bureau, CreditReport> respostas = new EnumMap<>(Bureau.class);
            Set<Bureau> indisponiveis = EnumSet.noneOf(Bureau.class);
            for (int i = 0; i < bureaus.size(); i++) {
                Bureau id = bureaus.get(i).bureau();
                try {
                    respostas.put(id, results.get(i).get());
                } catch (CancellationException | ExecutionException e) {
                    indisponiveis.add(id); // estourou o deadline ou falhou após a resiliência do adapter
                }
            }
            return ConsultaConsolidada.consolidar(cpf, bureaus.size(), respostas, indisponiveis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("consulta interrompida", e);
        }
    }
}
