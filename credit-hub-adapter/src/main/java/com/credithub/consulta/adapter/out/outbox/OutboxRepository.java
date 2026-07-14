package com.credithub.consulta.adapter.out.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEntity, String> {

    // Relay lê em ordem de criação (junto da chave por CPF, preserva ordenação ponta-a-ponta).
    List<OutboxEntity> findAllByOrderByCreatedAtAsc();
}
