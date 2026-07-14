# Credit Hub

Hub interno de consulta a bureaus de crédito (Serasa, Quod, Boa Vista).

## Arquitetura
Projeto utiliza Spring Boot 3.5.16 e Java 21, baseando-se em arquitetura hexagonal (Portas e Adaptadores). A lógica de domínio independe de frameworks.
Para garantir resiliência e concorrência, o aplicativo implementa o padrão scatter-gather, executando chamadas a múltiplos bureaus através de Virtual Threads, com um deadline global.
Para resolver problemas de dupla escrita, o sistema adota o padrão Transactional Outbox, que garante que os eventos sejam publicados atomicamente via banco de dados para os brokers de mensageria (Kafka).

## Módulos
- **credit-hub-domain**: Regras de negócio puras (sem dependências externas).
- **credit-hub-application**: Casos de uso e portas de entrada/saída.
- **credit-hub-adapter**: Implementação técnica (Web, JPA, Kafka, Resilience4j).
- **credit-hub-bootstrap**: Configuração, entry point e wiring dos módulos.
- **credit-hub-events**: Contratos e esquemas Avro compartilhados.
- **audit-service**: Serviço consumidor idempotente, responsável por manter a trilha de auditoria.

## Tecnologias e Infraestrutura
- Java 21 + Virtual Threads
- Spring Boot
- Resilience4j (Bulkhead, Circuit Breaker, Retry)
- PostgreSQL
- Apache Kafka + Confluent Schema Registry (Avro)
- Docker Compose & WireMock (Para stubs)

## Como executar
1. Suba a infraestrutura necessária (Postgres, Kafka, Schema Registry, WireMock):
   ```bash
   docker compose up -d
   ```
2. Compile os schemas Avro e construa o projeto:
   ```bash
   ./gradlew build
   ```
3. Execute o módulo principal de consultas:
   ```bash
   ./gradlew :credit-hub-bootstrap:bootRun
   ```
4. Em outra aba de terminal, execute o `audit-service`:
   ```bash
   ./gradlew :audit-service:bootRun
   ```

## Roadmap

- [x] Sprint 1-3: Espinha síncrona com Virtual Threads, Scatter-Gather e resiliência com Resilience4j
- [x] Sprint 4: Adicionado Transactional Outbox ao credit-query-service e serviço idempotente audit-service consumindo Kafka + Schema Registry.
