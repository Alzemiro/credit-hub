package com.credithub.audit;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "audit_log")
public class AuditLogEntity {

    @Id
    private String queryId; // This guarantees deduplication (idempotence)
    
    private String cpf;
    private long timestamp;
    private int bureausConsultados;
    private String confianca;

    protected AuditLogEntity() {}

    public AuditLogEntity(String queryId, String cpf, long timestamp, int bureausConsultados, String confianca) {
        this.queryId = queryId;
        this.cpf = cpf;
        this.timestamp = timestamp;
        this.bureausConsultados = bureausConsultados;
        this.confianca = confianca;
    }

    public String getQueryId() { return queryId; }
    public String getCpf() { return cpf; }
    public long getTimestamp() { return timestamp; }
    public int getBureausConsultados() { return bureausConsultados; }
    public String getConfianca() { return confianca; }
}
