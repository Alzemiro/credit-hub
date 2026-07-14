package com.credithub.decision;

import com.credithub.consulta.event.ConsultaCreditoRealizada;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext
@EmbeddedKafka(partitions = 1)
public class DecisionConsumerTest {

    @Autowired
    private KafkaTemplate<String, ConsultaCreditoRealizada> kafkaTemplate;

    @MockitoBean
    private DecisionDltRepository dltRepository;

    @MockitoSpyBean
    private DecisionConsumer decisionConsumer;

    @Test
    public void testNonBlockingRetriesAndDlt() throws Exception {
        // Envia mensagem poison (cpf="INVALIDO")
        ConsultaCreditoRealizada poison = ConsultaCreditoRealizada.newBuilder()
                .setQueryId("q-poison")
                .setCpf("99999999999")
                .setTimestamp(System.currentTimeMillis())
                .setBureausConsultados(3)
                .setConfianca("COMPLETA")
                .build();

        // Envia mensagem válida logo em seguida
        ConsultaCreditoRealizada valid = ConsultaCreditoRealizada.newBuilder()
                .setQueryId("q-valid")
                .setCpf("12345678909")
                .setTimestamp(System.currentTimeMillis())
                .setBureausConsultados(3)
                .setConfianca("COMPLETA")
                .build();

        kafkaTemplate.send(new ProducerRecord<>("consulta-credito-event", "q-poison", poison));
        kafkaTemplate.send(new ProducerRecord<>("consulta-credito-event", "q-valid", valid));

        // A mensagem válida deve ser processada com sucesso quase imediatamente,
        // comprovando que a partição principal não está bloqueada
        verify(decisionConsumer, timeout(5000)).consume(argThat(e -> e.getQueryId().equals("q-valid")));

        // A poison message será processada 3 vezes no total (1 tentativa + 2 retries de 1s,2s = ~3s)
        verify(decisionConsumer, timeout(10000).times(3)).consume(argThat(e -> e.getQueryId().equals("q-poison")));

        // E finalmente, deve cair no DLT
        verify(decisionConsumer, timeout(10000)).dlt(argThat(e -> e.getQueryId().equals("q-poison")), any());
    }
}
