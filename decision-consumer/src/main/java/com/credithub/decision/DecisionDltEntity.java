package com.credithub.decision;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "decision_dlt")
public class DecisionDltEntity {

    @Id
    private String queryId;

    private String cpf;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    private Instant createdAt;

    protected DecisionDltEntity() {}

    public DecisionDltEntity(String queryId, String cpf, String errorMessage) {
        this.queryId = queryId;
        this.cpf = cpf;
        this.errorMessage = errorMessage;
        this.createdAt = Instant.now();
    }

    public String getQueryId() { return queryId; }
    public String getCpf() { return cpf; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
}
