package com.credithub.consulta.adapter.in.web;

import com.credithub.consulta.adapter.out.outbox.OutboxWriter;
import com.credithub.consulta.application.CreditQueryService;
import com.credithub.consulta.domain.ConsultaConsolidada;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;


@RestController
@RequestMapping("/consultas")
class ConsultaController {

    private final CreditQueryService service;
    private final OutboxWriter outboxWriter;

    ConsultaController(CreditQueryService service, OutboxWriter outboxWriter) {
        this.service = service;
        this.outboxWriter = outboxWriter;
    }

    @PostMapping
    ConsultaConsolidada consultar(@RequestBody ConsultaRequest req) {
        // Valida o formato (11 dígitos), não o dígito verificador: os CPFs de teste
        // 00000000000/99999999999 não passam no DV, mas são cenários válidos (ver CLAUDE.md).
        if (req == null || req.cpf() == null || !req.cpf().matches("\\d{11}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cpf deve conter 11 dígitos");
        }
        ConsultaConsolidada consolidada = service.consultar(req.cpf());  // scatter-gather: fora de transação
        outboxWriter.registrar(consolidada);                            // transação curta: só o INSERT
        return consolidada;
    }
}
