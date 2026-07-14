package com.credithub.consulta.adapter.in.web;

import com.credithub.consulta.application.CreditQueryService;
import com.credithub.consulta.domain.ConsultaConsolidada;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.credithub.consulta.adapter.out.outbox.OutboxEntity;
import com.credithub.consulta.adapter.out.outbox.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@RestController
@RequestMapping("/consultas")
class ConsultaController {

    private final CreditQueryService service;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    ConsultaController(CreditQueryService service, OutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.service = service;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    // ponytail: devolve o domínio direto; criar ConsultaResponse quando o contrato web divergir do domínio.
    @PostMapping
    @Transactional
    ConsultaConsolidada consultar(@RequestBody ConsultaRequest req) {
        if (req == null || req.cpf() == null || req.cpf().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cpf é obrigatório");
        }
        ConsultaConsolidada consolidada = service.consultar(req.cpf());
        
        try {
            String queryId = UUID.randomUUID().toString();
            String payload = objectMapper.writeValueAsString(consolidada);
            OutboxEntity outbox = new OutboxEntity(queryId, "ConsultaCreditoRealizada", payload);
            outboxRepository.save(outbox);
        } catch (Exception e) {
            throw new RuntimeException("Erro ao serializar evento para outbox", e);
        }
        
        return consolidada;
    }
}
