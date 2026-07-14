package com.credithub.consulta.adapter.out;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;

/** Request factory compartilhada pelos adapters de bureau. */
@Configuration
class BureauHttpConfig {

    // HTTP/1.1 explícito: o HttpClient do JDK trata o RST_STREAM(CANCEL) do Jetty/WireMock
    // sobre HTTP/2 como erro mesmo com a resposta já recebida. HTTP/2 não traz ganho aqui.
    @Bean
    ClientHttpRequestFactory bureauRequestFactory() {
        HttpClient http11 = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        return new JdkClientHttpRequestFactory(http11);
    }
}
