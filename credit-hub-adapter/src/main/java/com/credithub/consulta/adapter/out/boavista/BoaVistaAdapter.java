package com.credithub.consulta.adapter.out.boavista;

import com.credithub.consulta.application.port.CreditBureauPort;
import com.credithub.consulta.domain.Bureau;
import com.credithub.consulta.domain.CreditReport;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class BoaVistaAdapter implements CreditBureauPort {

    private final RestClient client;

    public BoaVistaAdapter(RestClient.Builder builder, ClientHttpRequestFactory rf,
                           @Value("${bureau.boavista.base-url}") String baseUrl) {
        this.client = builder.baseUrl(baseUrl).requestFactory(rf).build();
    }

    @Override
    public Bureau bureau() {
        return Bureau.BOAVISTA;
    }

    @Override
    @Bulkhead(name = "boavista")
    @CircuitBreaker(name = "boavista")
    @Retry(name = "boavista")
    public CreditReport consultar(String cpf) {
        BoaVistaResponse r = client.post()
                .uri("/boavista/v1/relatorio")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new BoaVistaRequest(cpf))
                .retrieve()
                .body(BoaVistaResponse.class);
        return toDomain(r);
    }

    // Traduz o formato da BoaVista para o domínio (anti-corruption layer).
    static CreditReport toDomain(BoaVistaResponse r) {
        return new CreditReport(
                r.cpf(),
                r.consumer().name(),
                r.creditScore().points(),
                r.creditScore().band(),
                r.registration(),
                r.debts().hasDebt(),
                r.debts().total());
    }

    record BoaVistaRequest(String cpf) {
    }
}
