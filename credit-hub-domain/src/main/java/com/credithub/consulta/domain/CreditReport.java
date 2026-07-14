package com.credithub.consulta.domain;

import java.math.BigDecimal;

/** Resultado de uma consulta de crédito, independente do bureau de origem. */
public record CreditReport(
        String cpf,
        String nome,
        int score,
        String faixaScore,
        String situacaoCpf,
        boolean possuiPendencias,
        BigDecimal valorPendencias) {
}
