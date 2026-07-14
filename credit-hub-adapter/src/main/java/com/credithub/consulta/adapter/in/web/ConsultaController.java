package com.credithub.consulta.adapter.in.web;

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

    ConsultaController(CreditQueryService service) {
        this.service = service;
    }

    // ponytail: devolve o domínio direto; criar ConsultaResponse quando o contrato web divergir do domínio.
    @PostMapping
    ConsultaConsolidada consultar(@RequestBody ConsultaRequest req) {
        if (req == null || req.cpf() == null || req.cpf().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cpf é obrigatório");
        }
        return service.consultar(req.cpf());
    }
}
