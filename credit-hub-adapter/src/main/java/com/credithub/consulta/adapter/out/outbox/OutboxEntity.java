package com.credithub.consulta.adapter.out.outbox;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;

@Entity
@Table(name = "outbox")
public class OutboxEntity {

    @Id
    private String eventId; // Will be the queryId
    
    private String eventType;
    
    // We can store the payload as JSON string or byte array
    @Column(columnDefinition = "TEXT")
    private String payload;

    protected OutboxEntity() {}

    public OutboxEntity(String eventId, String eventType, String payload) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.payload = payload;
    }

    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
}
