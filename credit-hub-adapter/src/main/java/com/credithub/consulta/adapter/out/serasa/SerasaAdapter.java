package com.credithub.consulta.adapter.out.serasa;

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
public class SerasaAdapter implements CreditBureauPort {

    private final RestClient client;

    public SerasaAdapter(RestClient.Builder builder, ClientHttpRequestFactory rf,
                         @Value("${bureau.serasa.base-url}") String baseUrl) {
        this.client = builder.baseUrl(baseUrl).requestFactory(rf).build();
    }

    @Override
    public Bureau bureau() {
        return Bureau.SERASA;
    }

    // Resiliência isolada por bureau. Chamada síncrona: roda numa virtual thread do scatter-gather.
    // Sem fallback: em falha, a exceção sobe e o caso de uso conta este bureau como indisponível.
    @Override
    @Bulkhead(name = "serasa")
    @CircuitBreaker(name = "serasa")
    @Retry(name = "serasa")
    public CreditReport consultar(String cpf) {
        SerasaResponse resp = client.post()
                .uri("/v1/consultas")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new SerasaRequest(cpf))
                .retrieve()
                .body(SerasaResponse.class);
        return toDomain(resp);
    }

    static CreditReport toDomain(SerasaResponse r) {
        return new CreditReport(
                r.cpf(),
                r.nome(),
                r.score().valor(),
                r.score().faixa(),
                r.situacaoCpf(),
                r.pendenciasFinanceiras().possui(),
                r.pendenciasFinanceiras().valorTotal());
    }

    record SerasaRequest(String cpf) {
    }
}
