package com.credithub.consulta.application.port;

import com.credithub.consulta.domain.Bureau;
import com.credithub.consulta.domain.CreditReport;

/** Porta de saída: um bureau de crédito. Síncrona; lança em falha (o agregador conta como indisponível). */
public interface CreditBureauPort {

    Bureau bureau();

    CreditReport consultar(String cpf);
}
