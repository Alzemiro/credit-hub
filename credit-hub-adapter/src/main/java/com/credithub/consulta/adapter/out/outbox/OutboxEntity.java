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

    protected OutboxEntity() {}

    public OutboxEntity(String eventId, String eventType, String payload) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = Instant.now();
    }

    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
}
