package com.credithub.audit;

import com.credithub.consulta.event.ConsultaCreditoRealizada;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class AuditConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuditConsumer.class);
    
    private final AuditLogRepository repository;

    public AuditConsumer(AuditLogRepository repository) {
        this.repository = repository;
    }

    @KafkaListener(topics = "consulta-credito-event", groupId = "audit-service-group")
    public void consume(ConsultaCreditoRealizada event) {
        log.info("Recebido evento para queryId: {}", event.getQueryId());
        
        // Idempotência garantida pelo queryId como @Id
        if (repository.existsById(event.getQueryId())) {
            log.info("Evento já processado (dedup): {}", event.getQueryId());
            return;
        }

        AuditLogEntity entity = new AuditLogEntity(
                event.getQueryId(),
                event.getCpf(),
                event.getTimestamp(),
                event.getBureausConsultados(),
                event.getConfianca()
        );
        repository.save(entity);
        log.info("Trilha de auditoria persistida para queryId: {}", event.getQueryId());
    }
}
