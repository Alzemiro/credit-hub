package com.credithub.config;

import com.credithub.consulta.application.CreditQueryService;
import com.credithub.consulta.application.port.CreditBureauPort;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/** Wiring dos casos de uso (application é framework-free). */
@Configuration
public class UseCaseConfig {

    // Spring injeta todos os beans CreditBureauPort (Serasa/Quod/BoaVista) na lista.
    @Bean
    CreditQueryService creditQueryService(List<CreditBureauPort> bureaus,
                                          @Value("${consulta.deadline-ms:3000}") long deadlineMs) {
        return new CreditQueryService(bureaus, Duration.ofMillis(deadlineMs), contextAwareVirtualThreadExecutor());
    }

    /**
     * Fábrica de executor por-request: cada get() cria um executor de virtual threads embrulhado
     * pelo ContextExecutorService, que captura o ContextSnapshot na thread chamadora (a da request,
     * com o trace-context ativo) e o restaura em cada virtual thread. Assim os spans dos bureaus
     * ficam filhos do span da request. O Micrometer fica confinado aqui (bootstrap); a application
     * só enxerga java.util.concurrent.
     */
    private Supplier<ExecutorService> contextAwareVirtualThreadExecutor() {
        ContextSnapshotFactory factory = ContextSnapshotFactory.builder().build();
        return () -> ContextExecutorService.wrap(
                Executors.newVirtualThreadPerTaskExecutor(),
                factory::captureAll);
    }
}
