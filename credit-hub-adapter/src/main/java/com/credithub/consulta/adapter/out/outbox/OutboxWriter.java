package com.credithub.consulta.adapter.out.outbox;

import com.credithub.consulta.domain.ConsultaConsolidada;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Grava o evento de consulta na outbox numa transação curta — só a escrita, sem I/O externo dentro. */
@Component
public class OutboxWriter {

    private final OutboxRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void registrar(ConsultaConsolidada consolidada) {
        try {
            String queryId = UUID.randomUUID().toString();
            String payload = objectMapper.writeValueAsString(consolidada);
            repository.save(new OutboxEntity(queryId, "ConsultaCreditoRealizada", payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar evento para a outbox", e);
        }
    }
}