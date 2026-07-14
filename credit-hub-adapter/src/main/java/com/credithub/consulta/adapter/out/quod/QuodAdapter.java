package com.credithub.consulta.adapter.out.quod;

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
public class QuodAdapter implements CreditBureauPort {

    private final RestClient client;

    public QuodAdapter(RestClient.Builder builder, ClientHttpRequestFactory rf,
                       @Value("${bureau.quod.base-url}") String baseUrl) {
        this.client = builder.baseUrl(baseUrl).requestFactory(rf).build();
    }

    @Override
    public Bureau bureau() {
        return Bureau.QUOD;
    }

    @Override
    @Bulkhead(name = "quod")
    @CircuitBreaker(name = "quod")
    @Retry(name = "quod")
    public CreditReport consultar(String cpf) {
        QuodResponse r = client.post()
                .uri("/quod/score")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new QuodRequest(cpf))
                .retrieve()
                .body(QuodResponse.class);
        return toDomain(r);
    }

    // Traduz o formato da Quod para o domínio (anti-corruption layer).
    static CreditReport toDomain(QuodResponse r) {
        return new CreditReport(
                r.documento(),        // Quod chama o CPF de "documento"
                r.nomeCompleto(),
                r.pontuacao(),        // "pontuacao" -> score
                r.classeRisco(),      // faixa por classe de risco (A/B/C...)
                r.statusDocumento(),
                r.dividas().temPendencia(),
                r.dividas().valor());
    }

    record QuodRequest(String documento) {
    }
}
