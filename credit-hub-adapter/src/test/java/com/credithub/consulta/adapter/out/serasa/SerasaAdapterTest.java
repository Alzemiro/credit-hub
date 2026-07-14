package com.credithub.consulta.adapter.out.serasa;

import com.credithub.consulta.domain.CreditReport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SerasaAdapterTest {

    @Test
    void mapeiaRespostaSerasaParaDominio() {
        var resp = new SerasaResponse(
                "12345678909",
                "MARIA SILVA SOUZA",
                new SerasaResponse.Score(782, "BOM"),
                "REGULAR",
                new SerasaResponse.Pendencias(false, 0, new BigDecimal("0.00")),
                "8f2a9c14",
                "2026-07-14T10:15:30Z");

        CreditReport report = SerasaAdapter.toDomain(resp);

        assertEquals("12345678909", report.cpf());
        assertEquals("MARIA SILVA SOUZA", report.nome());
        assertEquals(782, report.score());
        assertEquals("BOM", report.faixaScore());
        assertEquals("REGULAR", report.situacaoCpf());
        assertFalse(report.possuiPendencias());
        assertEquals(new BigDecimal("0.00"), report.valorPendencias());
    }
}
