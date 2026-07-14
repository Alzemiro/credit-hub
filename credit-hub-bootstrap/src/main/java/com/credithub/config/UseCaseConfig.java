package com.credithub.config;

import com.credithub.consulta.application.CreditQueryService;
import com.credithub.consulta.application.port.CreditBureauPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/** Wiring dos casos de uso (application é framework-free). */
@Configuration
public class UseCaseConfig {

    // Spring injeta todos os beans CreditBureauPort (Serasa/Quod/BoaVista) na lista.
    @Bean
    CreditQueryService creditQueryService(List<CreditBureauPort> bureaus,
                                          @Value("${consulta.deadline-ms:3000}") long deadlineMs) {
        return new CreditQueryService(bureaus, Duration.ofMillis(deadlineMs));
    }
}
