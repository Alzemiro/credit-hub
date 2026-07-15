package com.credithub.consulta.adapter.out.outbox;

import com.credithub.consulta.event.ConsultaCreditoRealizada;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@EnableScheduling
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository repository;
    private final KafkaTemplate<String, ConsultaCreditoRealizada> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;

    public OutboxRelay(OutboxRepository repository,
                       KafkaTemplate<String, ConsultaCreditoRealizada> kafkaTemplate,
                       ObjectMapper objectMapper,
                       Tracer tracer) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.tracer = tracer;
    }

    @Scheduled(fixedDelay = 2000)
    public void processOutbox() {
        List<OutboxEntity> events = repository.findAllByOrderByCreatedAtAsc();
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
                            .setTimestamp(Instant.now())   // Avro timestamp-millis -> java.time.Instant
                            .setBureausConsultados(node.path("respostas").size() + node.path("indisponiveis").size())
                            .setConfianca(node.path("confianca").asText())
                            .build();

                    publicar(entity, avroEvent);
                }
            } catch (Exception e) {
                log.error("Erro ao processar evento outbox: " + entity.getEventId(), e);
            }
        }
    }

    /**
     * Publica com o contexto de trace re-hidratado a partir do traceparent gravado na escrita.
     * O send roda DENTRO do escopo do span filho: assim a auto-instrumentação do produtor Kafka
     * injeta nos headers o traceparent deste span (filho do trace da request), e não o da thread
     * do @Scheduled — que nasceria num trace órfão. Por isso o header não é escrito na mão.
     */
    private void publicar(OutboxEntity entity, ConsultaCreditoRealizada avroEvent) {
        Span publishSpan = novoSpanPublish(entity.getTraceparent());
        try (var scope = tracer.withSpan(publishSpan)) {
            kafkaTemplate.send("consulta-credito-event", avroEvent.getCpf().toString(), avroEvent)
                    .whenComplete((result, ex) -> {
                        if (ex == null) {
                            repository.delete(entity);
                        } else {
                            log.error("Erro ao publicar evento no Kafka", ex);
                        }
                    });
        } finally {
            publishSpan.end();
        }
    }

    /** Cria o span de publish como filho do contexto gravado; sem traceparent, começa um trace novo. */
    private Span novoSpanPublish(String traceparent) {
        var builder = tracer.spanBuilder().name("outbox-publish");
        if (traceparent != null && traceparent.length() == 55) {
            String[] p = traceparent.split("-");
            TraceContext parent = tracer.traceContextBuilder()
                    .traceId(p[1])
                    .spanId(p[2])
                    .sampled("01".equals(p[3]))
                    .build();
            builder.setParent(parent);
        }
        return builder.start();
    }
}
