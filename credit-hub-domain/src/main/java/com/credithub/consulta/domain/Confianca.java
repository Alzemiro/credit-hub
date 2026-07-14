package com.credithub.consulta.domain;

/** Confiança da resposta agregada conforme quantos bureaus responderam no deadline. */
public enum Confianca {
    COMPLETA,      // todos responderam
    PARCIAL,       // alguns responderam
    INDISPONIVEL   // nenhum respondeu
}
