package com.credithub.consulta.adapter.out.serasa;

import java.math.BigDecimal;

/** Payload retornado pelo bureau Serasa (formato do WireMock stub). */
public record SerasaResponse(
        String cpf,
        String nome,
        Score score,
        String situacaoCpf,
        Pendencias pendenciasFinanceiras,
        String consultaId,
        String dataConsulta) {

    public record Score(int valor, String faixa) {
    }

    public record Pendencias(boolean possui, int quantidade, BigDecimal valorTotal) {
    }
}
