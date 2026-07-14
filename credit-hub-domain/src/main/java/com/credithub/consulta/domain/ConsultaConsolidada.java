package com.credithub.consulta.domain;

import java.util.Map;
import java.util.Set;

/** Resposta agregada do scatter-gather: quem respondeu, quem não, e a confiança resultante. */
public record ConsultaConsolidada(
        String cpf,
        Confianca confianca,
        Map<Bureau, CreditReport> respostas,
        Set<Bureau> indisponiveis) {

    /** Regra: todos = COMPLETA; nenhum = INDISPONIVEL; entre os dois = PARCIAL. */
    public static ConsultaConsolidada consolidar(
            String cpf, int totalConsultados,
            Map<Bureau, CreditReport> respostas, Set<Bureau> indisponiveis) {
        Confianca confianca;
        if (respostas.size() == totalConsultados) {
            confianca = Confianca.COMPLETA;
        } else if (respostas.isEmpty()) {
            confianca = Confianca.INDISPONIVEL;
        } else {
            confianca = Confianca.PARCIAL;
        }
        return new ConsultaConsolidada(cpf, confianca, respostas, indisponiveis);
    }
}
