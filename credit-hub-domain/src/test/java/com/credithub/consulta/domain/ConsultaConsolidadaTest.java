package com.credithub.consulta.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConsultaConsolidadaTest {

    private CreditReport qualquer() {
        return new CreditReport("1", "x", 700, "BOM", "REGULAR", false, BigDecimal.ZERO);
    }

    @Test
    void todosResponderam_confiancaCompleta() {
        Map<Bureau, CreditReport> respostas = new EnumMap<>(Bureau.class);
        for (Bureau b : Bureau.values()) {
            respostas.put(b, qualquer());
        }
        var c = ConsultaConsolidada.consolidar("1", 3, respostas, EnumSet.noneOf(Bureau.class));
        assertEquals(Confianca.COMPLETA, c.confianca());
    }

    @Test
    void algunsResponderam_confiancaParcial() {
        Map<Bureau, CreditReport> respostas = new EnumMap<>(Bureau.class);
        respostas.put(Bureau.SERASA, qualquer());
        var c = ConsultaConsolidada.consolidar("1", 3, respostas, EnumSet.of(Bureau.QUOD, Bureau.BOAVISTA));
        assertEquals(Confianca.PARCIAL, c.confianca());
    }

    @Test
    void ninguemRespondeu_confiancaIndisponivel() {
        var c = ConsultaConsolidada.consolidar("1", 3, new EnumMap<>(Bureau.class), EnumSet.allOf(Bureau.class));
        assertEquals(Confianca.INDISPONIVEL, c.confianca());
    }
}
