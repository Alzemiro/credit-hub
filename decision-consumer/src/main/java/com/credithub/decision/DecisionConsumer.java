package com.credithub.decision;

import com.credithub.consulta.event.ConsultaCreditoRealizada;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DecisionConsumer {

    private static final Logger log = LoggerFactory.getLogger(DecisionConsumer.class);

    private final DecisionDltRepository dltRepository;

    public DecisionConsumer(DecisionDltRepository dltRepository) {
        this.dltRepository = dltRepository;
    }

    @RetryableTopic(
            attempts = "3",
            backoff = @Backoff(delay = 1000, multiplier = 2.0),
            dltStrategy = DltStrategy.FAIL_ON_ERROR
    )
    @KafkaListener(topics = "consulta-credito-event", groupId = "decision-service-group")
    public void consume(ConsultaCreditoRealizada event) {
        log.info("Processando decisão para queryId: {}", event.getQueryId());
        
        // Simulação de poison message / payload inválido no nível de negócio
        // Para simular o erro E2E, definimos o CPF 99999999999 como um CPF envenenado.
        if (event.getCpf() == null || event.getCpf().toString().equals("99999999999")) {
            log.error("Payload inválido detectado para queryId: {}", event.getQueryId());
            throw new IllegalArgumentException("Payload inválido: CPF não pode ser processado");
        }
        
        log.info("Decisão de crédito processada com sucesso para: {}", event.getCpf());
    }

    @DltHandler
    @Transactional
    public void dlt(ConsultaCreditoRealizada event, @Header(KafkaHeaders.EXCEPTION_MESSAGE) String errorMessage) {
        log.error("Mensagem enviada para o DLT. queryId: {}, erro: {}", event.getQueryId(), errorMessage);
        
        DecisionDltEntity entity = new DecisionDltEntity(
                event.getQueryId().toString(), 
                event.getCpf() != null ? event.getCpf().toString() : null, 
                errorMessage
        );
        dltRepository.save(entity);
        log.info("Mensagem salva na tabela decision_dlt com sucesso.");
    }
}
