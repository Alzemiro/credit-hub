package com.credithub.consulta.adapter.out.quod;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/** Formato da Quod: CPF é "documento", score é "pontuacao", faixa é classe de risco, snake_case. */
public record QuodResponse(
        String documento,
        @JsonProperty("nome_completo") String nomeCompleto,
        int pontuacao,
        @JsonProperty("classe_risco") String classeRisco,
        @JsonProperty("status_documento") String statusDocumento,
        Dividas dividas) {

    public record Dividas(
            @JsonProperty("tem_pendencia") boolean temPendencia,
            BigDecimal valor) {
    }
}
