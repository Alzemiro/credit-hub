# Decisões de Arquitetura (DECISIONS.md)

## 1. Por que usar Transactional Outbox em vez de publicar direto no Kafka dentro do @Transactional?

**Contexto**: Durante a consulta de crédito, precisamos salvar a trilha de auditoria e garantir que o evento `ConsultaCreditoRealizada` seja publicado no Kafka para sistemas subsequentes (audit-service, decisão de crédito, perfil).

**A Decisão**: Utilizamos o padrão **Transactional Outbox**, onde o evento é salvo em uma tabela `outbox` na mesma transação `@Transactional` da operação, em vez de publicar diretamente no Kafka (`kafkaTemplate.send(...)` dentro da transação).

**Justificativa**:
Publicar diretamente em um message broker dentro de uma transação de banco de dados cria o problema de **Dual Write** (Escrita Dupla):
1. Se publicarmos no Kafka antes do commit do banco, o Kafka já terá recebido o evento. Se o commit falhar em seguida (ex: erro de constraints, falha de conexão), o sistema consumidor processará um evento de um estado que foi revertido.
2. Se publicarmos após o commit (ex: via `@TransactionalEventListener(phase = AFTER_COMMIT)`), se o processo falhar (crash da aplicação) logo após o commit do banco mas antes de atingir o Kafka, a informação é atualizada localmente, mas o evento nunca é disparado, resultando em perda do evento.

Com o **Transactional Outbox**, garantimos atomicidade: a tabela de outbox e o estado da aplicação estão no mesmo banco, então ou ambos são salvos ou nenhum é. Um processo assíncrono (relay) lê os eventos da tabela e garante que sejam entregues ao Kafka (at-least-once delivery).

**Consequências**:
- Garantia forte de consistência eventual sem perda de eventos.
- Requer uma thread/job adicional (`@Scheduled`) no adapter para realizar o relay das mensagens do banco para o Kafka.
- Consumidores precisam ser **idempotentes**, pois a entrega é *at-least-once* (pode haver duplicação se o relay falhar ao apagar da tabela outbox após envio). Implementamos a deduplicação no `audit-service` usando o `queryId` como chave primária.

> **Nuance deste projeto (outbox "degenerado")**: a consulta é *passthrough* e **não persiste estado de negócio** — a transação grava só a própria linha da outbox. Logo a justificativa real aqui **não é atomicidade com um estado** (não há estado a proteger); é **desacoplar a resposta HTTP da disponibilidade do Kafka** e garantir a trilha de auditoria (LGPD) mesmo com o broker fora. O argumento do dual-write acima continua válido como razão geral do padrão.

## 2. Por que a chave de partição do Kafka é o CPF, e não o queryId?

**Contexto**: O `OutboxRelay` publica `ConsultaCreditoRealizada` com uma chave de partição. O `queryId` é único por consulta; o CPF se repete entre consultas da mesma pessoa.

**A Decisão**: A chave de partição é o **CPF** (`avroEvent.getCpf()`). O `queryId` permanece no payload e é a chave de **deduplicação** do `audit-service`.

**Justificativa**: No Kafka a ordem só é garantida **dentro de uma partição**. Com `queryId` como chave, cada consulta cai numa partição possivelmente diferente e os eventos de um mesmo CPF podem ser consumidos fora de ordem — o que quebraria o futuro `profile-service` ("perfil mais recente por documento"). Com o CPF como chave, todas as consultas de uma pessoa vão para a mesma partição, preservando a ordem. Combinado com a leitura ordenada por `createdAt` no relay, a ordenação é confiável ponta-a-ponta.

**Trade-off**: risco de *hot partition* se um CPF concentrar volume desproporcional — aceitável para o domínio (consultas por pessoa não têm pico extremo); reavaliar se surgir gargalo.

## 3. Tracing em Virtual Threads no Scatter-Gather

**Contexto**: O `CreditQueryService` (scatter-gather) dispara os adapters de bureaus utilizando `Executors.newVirtualThreadPerTaskExecutor()`. Gostaríamos de rastrear essas chamadas filhas no Jaeger sob um mesmo Span pai de orquestração.

**A Decisão**: Deixamos as threads sem propagação automática do contexto de tracing no momento. O contexto do Micrometer/OpenTelemetry (via `ThreadLocal`) não se propaga nativamente para as virtual threads não gerenciadas pelo Spring.

**Justificativa**: 
Para propagar o contexto, precisaríamos utilizar os utilitários de Context Propagation do Micrometer (`ContextSnapshot` / `ContextPropagators`) dentro do `CreditQueryService`. Porém, esta classe reside no módulo `credit-hub-application`, que pela arquitetura hexagonal do projeto é **framework-free** e não deve depender do Micrometer ou Spring Actuator. Fazer o wrapping explícito das tarefas forçaria a inclusão de bibliotecas de infraestrutura na aplicação, quebrando a regra de ouro do design. 

**Trade-off**: Perde-se a correlação automática de Spans pai/filho no Jaeger para as sub-tarefas do scatter-gather (as chamadas aos bureaus podem ficar desconectadas do span pai de entrada), mas preserva-se a pureza da aplicação. Caso a rastreabilidade exija a correlação fina, a criação do Span manual ("scatter-gather-bureaus") e a propagação de contexto deverão ser resolvidas no adapter/controller ANTES de invocar o serviço da aplicação, ou injetando um executor context-aware proveniente do adapter.

## 4. Pipeline de Observabilidade (OTel Collector + Prometheus + Jaeger)

**Contexto**: O Jaeger 1.60 (`jaegertracing/all-in-one`) não gera métricas RED (Rate, Errors, Duration) nativamente dos traces recebidos para popular a sua aba "Monitor" (SPM - Service Performance Monitoring). Ele apenas consome métricas já calculadas a partir de um backend compatível, como o Prometheus.

**A Decisão**: Em vez de enviar traces diretamente do Spring Boot para o Jaeger (`4318`), adicionamos um **OpenTelemetry Collector** no meio do caminho. O Collector intercepta os traces, calcula as métricas RED através do conector `spanmetrics`, envia as métricas para o **Prometheus**, e encaminha os traces originais para o **Jaeger**. O Jaeger foi então configurado para ler essas métricas do Prometheus. Também adicionamos o `micrometer-registry-prometheus` para expor as métricas internas da JVM e do Resilience4j diretamente para o Prometheus via `/actuator/prometheus`.

**Justificativa**: 
Esta é a arquitetura moderna recomendada pelo ecossistema OpenTelemetry para obter a aba Monitor (SPM) operante no Jaeger. O uso do OTel Collector desacopla a lógica de extração de métricas do código da aplicação, permitindo que a aplicação faça apenas um push (OTLP) e a infraestrutura cuide da derivação de métricas. A integração do Micrometer complementa a visão RED com métricas de infraestrutura (circuit breakers, db pools) no Prometheus. 

**Trade-off**: Maior complexidade na infraestrutura local (Docker Compose) devido à inclusão de dois novos componentes (Collector e Prometheus), exigindo também a configuração de normalização de queries no Jaeger (`PROMETHEUS_QUERY_NORMALIZE_CALLS`). O benefício é uma malha de telemetria rica e pronta para produção.
