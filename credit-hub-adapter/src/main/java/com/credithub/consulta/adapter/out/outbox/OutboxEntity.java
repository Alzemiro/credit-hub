package com.credithub.consulta.adapter.out.outbox;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;

import java.time.Instant;

@Entity
@Table(name = "outbox")
public class OutboxEntity {

    @Id
    private String eventId; // Será o queryId

    private String eventType;

    // Podemos armazenar o payload como JSON (String) ou byte array
    @Column(columnDefinition = "TEXT")
    private String payload;

    // Ordem de criação: o relay lê por ela para preservar ordenação ponta-a-ponta.
    private Instant createdAt;

    // Contexto de trace da request original (formato W3C traceparent, 55 chars fixos), capturado
    // na escrita. O relay re-hidrata a partir dele para ligar o span de publish ao trace da request
    // (o polling roda numa thread do @Scheduled, sem o ThreadLocal da request). Nullable: se não
    // houver span ativo na escrita, fica null e a publicação vira um trace novo.
    @Column(length = 55)
    private String traceparent;

    protected OutboxEntity() {}

    public OutboxEntity(String eventId, String eventType, String payload, String traceparent) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.payload = payload;
        this.traceparent = traceparent;
        this.createdAt = Instant.now();
    }

    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
    public String getTraceparent() { return traceparent; }
}
