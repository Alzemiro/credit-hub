# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Contexto

Hub interno de consulta a bureaus de crédito (Serasa/Quod/BoaVista). Duas espinhas:
- **Hot path síncrono**: `POST /consultas` → scatter-gather nos bureaus, protegido por Resilience4j.
- **Espinha assíncrona**: Outbox → Kafka (auditoria, perfil, decisão de crédito).

Decisão central: **não forçar Kafka no caminho síncrono** — a consulta responde ao chamador sem depender do broker; o que é assíncrono sai por Outbox.

> ⚠️ **Estado atual vs. alvo.** O hot path está implementado com **scatter-gather nos 3 bureaus (Serasa/Quod/BoaVista)** sob deadline global. A **Espinha assíncrona** foi implementada via **Transactional Outbox**, produtor Kafka e eventos (Avro + Schema Registry). O `audit-service` consome de forma idempotente.

## Stack

Java 21 (toolchain), Spring Boot **3.5.16**, `io.spring.dependency-management` 1.1.7, Spring Kafka, Resilience4j (`resilience4j-spring-boot3` **2.2.0**), PostgreSQL 16, Confluent Schema Registry, WireMock 3, Docker Compose. Build: Gradle (wrapper 8.10.2, sem Gradle instalado na máquina).

## Arquitetura e regras de dependência

Monorepo Gradle multi-módulo, hexagonal. Dependência **sempre aponta pra dentro**:

```
bootstrap → adapter → application → domain
```

| Módulo | Papel | Regra confirmável |
|---|---|---|
| `credit-hub-domain` | Modelo puro (`CreditReport`) | Java puro, **zero** Spring/JPA/Kafka. `./gradlew :credit-hub-domain:dependencies` não deve listar nada de framework. |
| `credit-hub-application` | Casos de uso + portas (`CreditQueryService`, `CreditBureauPort`) | Framework-free. Depende **só** de `:credit-hub-domain`. `@Service`/`@Component` **não** entram aqui — o wiring é feito por `@Bean` em `UseCaseConfig` (bootstrap). |
| `credit-hub-adapter` | Adapters in (`web`) / out (`serasa`/`quod`/`boavista`) | Onde vivem web, RestClient, JPA, Kafka e **as anotações do Resilience4j**. |
| `credit-hub-bootstrap` | Único com `main()` e `application.yml` | **Único módulo com o plugin `org.springframework.boot`.** Os demais só aplicam `io.spring.dependency-management`. |

Regras não-negociáveis:
- Resiliência **por-bureau** é infraestrutura: `@Bulkhead`/`@CircuitBreaker`/`@Retry` só no adapter, nunca em application/domain.
- Orquestração **cross-bureau** (paralelismo + deadline global + agregação) é caso de uso: fica em `CreditQueryService` (application), sem framework.
- Cada adapter de bureau é **anti-corruption layer**: traduz o payload externo (`SerasaResponse`/`QuodResponse`/`BoaVistaResponse`, formatos bem diferentes) para o domínio via `toDomain(...)`. O domínio nunca vê o formato cru.
- A pureza do domínio hoje é garantida **só por convenção** (a dependência simplesmente não está lá) — não há ArchUnit/enforcement. Se adicionar regra automatizada, documente aqui.

## Design não-óbvio já no código (não "conserte" sem entender)

- **Scatter-gather com virtual threads + deadline global** (`CreditQueryService`). Dispara os `List<CreditBureauPort>` em paralelo via `Executors.newVirtualThreadPerTaskExecutor()` + `invokeAll(tasks, deadline)`. Quem não termina no deadline é cancelado → `CancellationException` → conta como indisponível. A **confiança** (`COMPLETA`/`PARCIAL`/`INDISPONIVEL`) sai da contagem de quem respondeu (regra em `ConsultaConsolidada.consolidar`).
- **O deadline global é a única autoridade de tempo** — de propósito **não há `@TimeLimiter` por-bureau**. Por isso os adapters são chamadas bloqueantes simples (a virtual thread torna o bloqueio barato), sem `CompletableFuture`. Não reintroduza TimeLimiter/async por perna: duplicaria a semântica de timeout.
- **Sem fallback por-bureau.** Em falha (após retry/breaker), o adapter **lança** — a degradação acontece no agregador, não na perna. Um fallback que devolvesse um `CreditReport` falso seria contado como "respondeu" e falsearia a confiança.
- **Anotações R4j direto no método do adapter** funcionam porque quem chama é outro bean (`CreditQueryService`) — passa pelo proxy AOP. Não há self-invocation aqui.
- **RestClient pinado em HTTP/1.1** via `BureauHttpConfig` (`ClientHttpRequestFactory` compartilhado pelos 3 adapters). O `HttpClient` do JDK trata o `RST_STREAM(CANCEL)` do Jetty/WireMock sobre HTTP/2 como erro mesmo com a resposta recebida.

## Convenções de código

- **Eventos** nomeados no passado: `<Agregado><FatoOcorrido>` (ex.: `ConsultaCreditoRealizada`). A espinha Kafka utiliza Avro gerado na compilação do módulo `credit-hub-events`.
- Pacote base `com.cwi.credithub`; feature em subpacote (`.consulta`), depois camada (`.adapter.in.web`, `.adapter.out.serasa`, `.application`, `.domain`).
- **Testes**: JUnit 5, unit puro (mappers em `SerasaAdapterTest`; regra de confiança em `ConsultaConsolidadaTest`). ⚠️ *Testcontainers e WireMock-em-teste são o alvo para integração, mas **ainda não são dependências** — hoje o WireMock só existe via Docker Compose, não nos testes.*

## Contradições / dívidas a corrigir (não descrever como se estivesse certo)

- **Cancelamento não interrompe a chamada HTTP imediatamente.** O `invokeAll` cancela a virtual thread no deadline, mas o bloqueio no JDK HttpClient só desenrola no interrupt — o stub lento de 8s pode seguir ocupando a thread por um instante após o deadline de 3s. Virtual thread é barata, então tolerável; se virar problema, um timeout de socket no `ClientHttpRequestFactory` é o teto.
- **`kafka-ui:latest`** no compose (tag flutuante) — fixar versão quando estabilizar.

## Como rodar

```bash
# Infra (Kafka KRaft, Schema Registry, Kafka UI, Postgres, WireMock)
docker compose up -d

# App — precisa de Postgres de pé e do WireMock p/ o bureau
./gradlew :credit-hub-bootstrap:bootRun

# Em outro terminal: App do Audit Service p/ consumir kafka
./gradlew :audit-service:bootRun

# Build completo / testes
./gradlew build
# Um teste só
./gradlew :credit-hub-adapter:test --tests "*SerasaAdapterTest"
# Provar pureza do domínio
./gradlew :credit-hub-domain:dependencies

# Recarregar stubs do WireMock após editar infra/wiremock/mappings/*.json
docker compose restart wiremock
```

Testar o hot path (CPF normal = sucesso; `00000000000` = 500 no stub, dispara retry/breaker/fallback):
```bash
curl -s -X POST http://localhost:8083/consultas -H "Content-Type: application/json" -d '{"cpf":"12345678909"}'
curl -s -X POST http://localhost:8083/consultas -H "Content-Type: application/json" -d '{"cpf":"00000000000"}'
```

| Serviço | URL |
|---|---|
| App (`POST /consultas`) | http://localhost:8083 |
| Audit Service           | http://localhost:8084 |
| Actuator (breaker) | `/actuator/circuitbreakers`, `/actuator/circuitbreakerevents/serasa`, `/actuator/health` |
| Kafka UI | http://localhost:8080 |
| Schema Registry | http://localhost:8081 |
| WireMock (bureau fake) | http://localhost:8082 (admin em `/__admin`) |
| Postgres | localhost:5432 — `credithub`/`credithub`, db `credithub` |
| Kafka broker | localhost:9092 |

> Portas do app deslocadas para 8083 porque 8080/8081/8082 são de kafka-ui/schema-registry/wiremock.

## Definição de "pronto" (por tarefa)

- O domínio permanece livre de framework (checar com `:credit-hub-domain:dependencies`).
- Decisões de design não-óbvias vão para **`DECISIONS.md`** com o trade-off.
- Ao concluir um sprint, atualizar o **`README.md`**: marcar o item no Roadmap, ajustar a seção correspondente e corrigir as instruções de execução se algo mudou — documentando **só o que existe no código**.
