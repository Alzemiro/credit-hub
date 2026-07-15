package com.credithub.consulta.adapter.out.outbox;

import com.credithub.consulta.domain.ConsultaConsolidada;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Grava o evento de consulta na outbox numa transação curta — só a escrita, sem I/O externo dentro. */
@Component
public class OutboxWriter {

    private final OutboxRepository repository;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;

    public OutboxWriter(OutboxRepository repository, ObjectMapper objectMapper, Tracer tracer) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.tracer = tracer;
    }

    @Transactional
    public void registrar(ConsultaConsolidada consolidada) {
        try {
            String queryId = UUID.randomUUID().toString();
            String payload = objectMapper.writeValueAsString(consolidada);
            repository.save(new OutboxEntity(queryId, "ConsultaCreditoRealizada", payload, currentTraceparent()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar evento para a outbox", e);
        }
    }

    /** Serializa o contexto de trace atual no formato W3C traceparent, ou null se não houver span. */
    private String currentTraceparent() {
        Span span = tracer.currentSpan();
        if (span == null) {
            return null; // sem span ativo: telemetria não deve quebrar a request
        }
        var ctx = span.context();
        String flags = Boolean.TRUE.equals(ctx.sampled()) ? "01" : "00";
        return "00-" + ctx.traceId() + "-" + ctx.spanId() + "-" + flags;
    }
}
