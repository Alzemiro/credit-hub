# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **Manutenção:** este arquivo descreve o **código que existe**, não o alvo. Sempre que o design mudar (ex.: um pattern for adicionado/removido), atualize a seção correspondente **no mesmo commit**. A versão anterior deste arquivo ficou defasada — não repita.

## Contexto

Hub interno de consulta a bureaus de crédito (Serasa/Quod/BoaVista). Duas espinhas:
- **Hot path síncrono**: `POST /consultas` → scatter-gather nos 3 bureaus em paralelo, sob um **deadline global**. Cada perna é protegida por Resilience4j. Responde ao chamador sem depender do Kafka.
- **Espinha assíncrona**: cada consulta grava um evento na **outbox**; um relay publica no Kafka (Avro + Schema Registry); consumidores fazem auditoria e decisão.

Decisão central: **não forçar Kafka no caminho síncrono** — a consulta responde mesmo com o broker fora; o que é assíncrono sai por outbox.

## Estado atual

**Implementado:** 3 adapters de bureau + scatter-gather com agregação parcial; resiliência por bureau (bulkhead + circuit breaker + retry); Outbox + relay publicando Avro; `audit-service` (consumer idempotente); `decision-consumer` (`@RetryableTopic` + DLT). Observabilidade com OpenTelemetry (Tracing OTLP), Micrometer (Métricas) + Prometheus + Jaeger SPM. Testes de carga (k6). Stubs WireMock para sucesso, erro 500 e latência de 8s.

**Ainda NÃO implementado (não descrever como se existisse):** `profile-service` / read model CQRS ("perfil mais recente por documento"); cache com TTL; deploy cloud (Confluent Cloud + Container Apps + Terraform). WireMock só existe via Docker Compose — **não** como dependência de teste (não há Testcontainers).

## Stack

Java 21 (toolchain). Spring Boot **3.5.16**, `io.spring.dependency-management` 1.1.7, plugin Avro `com.github.davidmc24.gradle.plugin.avro` 1.9.1. Spring Kafka, Resilience4j (`resilience4j-spring-boot3` **2.2.0**), PostgreSQL 16, Confluent (Kafka 7.6.1 KRaft, Schema Registry 7.6.1), WireMock 3.9.1. Observabilidade: Jaeger 1.60, Prometheus 2.53, OpenTelemetry Collector Contrib 0.104.0. Testes: k6 0.52.0. `group = com.credithub`. Build: Gradle wrapper (sem Gradle instalado na máquina).

## Arquitetura e regras de dependência

Monorepo Gradle multi-módulo. O núcleo é hexagonal; auditoria e decisão são serviços consumidores separados. Dependência do núcleo **aponta sempre pra dentro**: `bootstrap → adapter → application → domain`.

| Módulo | Papel | Regra confirmável |
|---|---|---|
| `credit-hub-domain` | Modelo puro (`CreditReport`, `ConsultaConsolidada`, `Bureau`, `Confianca`) | Java puro, **zero** Spring/JPA/Kafka. `./gradlew :credit-hub-domain:dependencies` não lista framework. |
| `credit-hub-application` | Casos de uso + portas (`CreditQueryService`, `CreditBureauPort`) | Framework-free. Depende **só** de `:credit-hub-domain`. Sem `@Service`/`@Component` — o wiring é `@Bean` em `UseCaseConfig` (bootstrap). |
| `credit-hub-adapter` | Adapters in (`web`) / out (`serasa`/`quod`/`boavista`, `outbox`) | Onde vivem web, RestClient, JPA, Kafka e **as anotações do Resilience4j**. |
| `credit-hub-bootstrap` | Único com `main()` e `application.yml` | **Único módulo com o plugin `org.springframework.boot`.** Os demais só aplicam `io.spring.dependency-management`. |
| `credit-hub-events` | Schema Avro `ConsultaCreditoRealizada.avsc` | Gera as classes Avro (plugin avro). |
| `audit-service` | Consumer de auditoria (app Spring Boot própria) | Grupo `audit-service-group`. Dedup por `queryId` (@Id). |
| `decision-consumer` | Pipeline de decisão + DLT (app Spring Boot própria) | Grupo `decision-service-group`. `@RetryableTopic` + `@DltHandler`. |

Regras não-negociáveis:
- Resiliência é **infraestrutura**: `@Bulkhead`/`@CircuitBreaker`/`@Retry` só no adapter, nunca em application/domain.
- Adapter de bureau é **anti-corruption layer**: traduz o payload externo (`SerasaResponse`/`QuodResponse`/`BoaVistaResponse`) para o domínio (`CreditReport`). O domínio nunca vê o formato cru.
- Pureza do domínio garantida **só por convenção** (a dependência não está lá) — não há ArchUnit. Se automatizar, documente aqui.

## Design não-óbvio já no código (não "conserte" sem entender)

- **Adapters síncronos + deadline global (não há TimeLimiter).** A porta `CreditBureauPort` é síncrona e lança em falha. O `CreditQueryService` roda os bureaus com `Executors.newVirtualThreadPerTaskExecutor()` + `invokeAll(tasks, deadline)`; quem não terminar no deadline (`consulta.deadline-ms=3000`) é **cancelado** e entra em `indisponiveis`. **O timeout é responsabilidade do orquestrador, não de cada perna** — por isso o TimeLimiter e o split adapter/client de versões antigas foram removidos.
- **Sem `fallbackMethod` no adapter.** Em falha após a resiliência, a exceção sobe; o agregador conta o bureau como indisponível. A degradação é decidida na agregação (`Confianca.PARCIAL`/`INDISPONIVEL`), não por fallback local.
- **Ordem dos aspectos: `Retry ( CircuitBreaker ( Bulkhead ( chamada ) ) )`.** Sem RateLimiter/TimeLimiter. `record-exceptions`/`retry-exceptions` cobrem só `RestClientException` e `IOException` — logo `BulkheadFullException` **não** abre o breaker nem é retentada (falha rápido → indisponível).
- **Breaker/retry/bulkhead por bureau** (instances `serasa`/`quod`/`boavista` herdando de um `default` via `base-config`). Cada bulkhead é um semáforo isolado (`max-concurrent-calls: 8`, `max-wait-duration: 0`), então um bureau saturado não consome a capacidade dos outros.
- **RestClient pinado em HTTP/1.1** (`BureauHttpConfig`, `JdkClientHttpRequestFactory` + `HttpClient.Version.HTTP_1_1`): o `HttpClient` do JDK trata o `RST_STREAM(CANCEL)` do WireMock sobre HTTP/2 como erro mesmo com resposta recebida.

## Convenções de código

- **Eventos** nomeados no passado: `ConsultaCreditoRealizada`. Tópico `consulta-credito-event`. Serialização **Avro + Schema Registry** (`KafkaAvroSerializer`/`Deserializer`, `specific.avro.reader=true`). Campo `timestamp` é `timestamp-millis` → gerado como `java.time.Instant` (JSR310); converte-se para epoch-millis só na fronteira de persistência do `audit-service`.
- **Chave de partição = CPF** (não `queryId`): mantém todas as consultas de um mesmo documento na mesma partição, preservando ordem para o futuro `profile-service`. O `queryId` fica no payload e é a chave de **dedup** do `audit-service` (ver [DECISIONS.md](DECISIONS.md)).
- Pacote base `com.credithub`; feature em `.consulta`, depois camada (`.adapter.in.web`, `.adapter.out.serasa`, `.application`, `.domain`).
- **Testes**: JUnit 5 (`SerasaAdapterTest`, `ConsultaConsolidadaTest`, `DecisionConsumerTest`). Testcontainers ainda **não** é dependência.

## Contradições / dívidas conscientes (não descrever como se estivesse certo)

- **Outbox degenerado.** A consulta é passthrough e **não persiste estado de negócio** — só a própria linha da outbox. Logo a justificativa NÃO é "atomicidade com o estado" (não há estado); é **desacoplar a resposta da disponibilidade do Kafka** + garantir a auditoria mesmo com o broker fora. Saber essa distinção.
- **"Poison message" é exceção de negócio** (CPF `99999999999` lança no `decision-consumer`), não poison de desserialização. A poison real (bytes/schema inválidos) ocorre antes do listener e exige `ErrorHandlingDeserializer` para não virar loop no container — não implementado.
- **`OutboxRelay` varre a tabela a cada 2s** — agora **ordenada por `createdAt`** (`findAllByOrderByCreatedAtAsc`), mas ainda **sem filtro de status nem limite**. O `delete` é assíncrono no callback do `send` → pode **republicar** (at-least-once; por isso o consumer é idempotente). Evoluir para status + limite, ou delete síncrono na transação do tick.
- **Dedup do audit é check-then-act** (`existsById` depois `save`) — corrida sob concorrência. O robusto é confiar na PK e tratar a violação.
- **`ddl-auto: update`** — ok pra demo; produção usaria Flyway/Liquibase.
- **`kafka-ui:latest`** (tag flutuante) no compose — fixar versão.

## Como rodar

```bash
# Infra (Kafka KRaft, Schema Registry, Kafka UI, Postgres, WireMock)
docker compose up -d

# App principal (precisa de Postgres e WireMock de pé)
./gradlew :credit-hub-bootstrap:bootRun
# Consumidores (apps separadas)
./gradlew :audit-service:bootRun
./gradlew :decision-consumer:bootRun

# Build / testes
./gradlew build
./gradlew :credit-hub-domain:dependencies   # provar pureza do domínio
docker compose restart wiremock              # recarregar stubs após editar mappings

# Testes de Carga (k6)
docker compose run --rm k6 run /scripts/01-latencia.js
docker compose run --rm k6 run /scripts/02-breaker.js
docker compose run --rm k6 run /scripts/03-bulkhead.js
docker compose run --rm k6 run /scripts/04-capacidade.js
```

Testar o fluxo (CPF normal = sucesso; `00000000000` = 500 no Serasa → retry/breaker; `99999999999` = poison no decision-consumer → DLT):
```bash
curl -s -X POST http://localhost:8083/consultas -H "Content-Type: application/json" -d '{"cpf":"12345678909"}'
curl -s -X POST http://localhost:8083/consultas -H "Content-Type: application/json" -d '{"cpf":"00000000000"}'
```

| Serviço | URL |
|---|---|
| App (`POST /consultas`) | http://localhost:8083 |
| Actuator (métricas e resiliência) | `/actuator/prometheus`, `/actuator/circuitbreakers`, `/actuator/health` |
| decision-consumer | http://localhost:8085 |
| Kafka UI | http://localhost:8080 |
| Schema Registry | http://localhost:8081 |
| WireMock | http://localhost:8082 (`/__admin`) |
| Postgres | localhost:5432 — `credithub`/`credithub`, db `credithub` |
| Kafka broker | localhost:9092 |
| Jaeger UI (Traces & SPM) | http://localhost:16686 |
| Prometheus | http://localhost:9090 |

## Definição de "pronto" (por tarefa)

- O domínio permanece livre de framework (`:credit-hub-domain:dependencies`).
- **Este CLAUDE.md e o README refletem o código real** — atualizar no mesmo commit; documentar só o que existe.
- Decisões de design não-óbvias vão para `DECISIONS.md` com o trade-off.