package com.credithub.consulta.adapter.out.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxRepository extends JpaRepository<OutboxEntity, String> {
}
