package com.credithub.decision;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DecisionDltRepository extends JpaRepository<DecisionDltEntity, String> {
}
