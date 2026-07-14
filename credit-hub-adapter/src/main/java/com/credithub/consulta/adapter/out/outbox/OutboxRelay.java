package com.credithub.consulta.adapter.out.outbox;

import com.credithub.consulta.event.ConsultaCreditoRealizada;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@EnableScheduling
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    
    private final OutboxRepository repository;
    private final KafkaTemplate<String, ConsultaCreditoRealizada> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public OutboxRelay(OutboxRepository repository, 
                       KafkaTemplate<String, ConsultaCreditoRealizada> kafkaTemplate,
                       ObjectMapper objectMapper) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 2000)
    public void processOutbox() {
        List<OutboxEntity> events = repository.findAll();
        if (events.isEmpty()) {
            return;
        }

        for (OutboxEntity entity : events) {
            try {
                if ("ConsultaCreditoRealizada".equals(entity.getEventType())) {
                    JsonNode node = objectMapper.readTree(entity.getPayload());
                    
                    ConsultaCreditoRealizada avroEvent = ConsultaCreditoRealizada.newBuilder()
                            .setQueryId(entity.getEventId())
                            .setCpf(node.path("cpf").asText())
                            .setTimestamp(System.currentTimeMillis())
                            .setBureausConsultados(node.path("respostas").size() + node.path("indisponiveis").size())
                            .setConfianca(node.path("confianca").asText())
                            .build();

                    kafkaTemplate.send("consulta-credito-event", entity.getEventId(), avroEvent)
                            .whenComplete((result, ex) -> {
                                if (ex == null) {
                                    repository.delete(entity);
                                } else {
                                    log.error("Erro ao publicar evento no Kafka", ex);
                                }
                            });
                }
            } catch (Exception e) {
                log.error("Erro ao processar evento outbox: " + entity.getEventId(), e);
            }
        }
    }
}
