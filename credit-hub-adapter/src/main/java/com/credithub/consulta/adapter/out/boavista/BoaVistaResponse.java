package com.credithub.consulta.adapter.out.boavista;

import java.math.BigDecimal;

/** Formato da BoaVista: nomes em inglês e mais aninhado (consumer/creditScore/debts). */
public record BoaVistaResponse(
        String cpf,
        Consumer consumer,
        CreditScore creditScore,
        String registration,
        Debts debts) {

    public record Consumer(String name) {
    }

    public record CreditScore(int points, String band) {
    }

    public record Debts(boolean hasDebt, BigDecimal total, int count) {
    }
}
