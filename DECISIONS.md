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

## 3. Tracing em Virtual Threads no Scatter-Gather (RESOLVIDO — executor context-aware injetado)

**Contexto**: O `CreditQueryService` (scatter-gather) dispara os adapters de bureaus em virtual threads. O contexto de tracing do Micrometer/OpenTelemetry vive em `ThreadLocal` e **não** se propaga nativamente para virtual threads criadas à mão — os spans dos bureaus ficavam órfãos do span da request.

**A Decisão**: O `CreditQueryService` **não cria mais** o executor internamente; recebe um `Supplier<ExecutorService>` no construtor. O wiring (`UseCaseConfig`, no `bootstrap`) fornece um executor de virtual threads embrulhado por `ContextExecutorService` (`io.micrometer:context-propagation`), que captura um `ContextSnapshot` na thread da request e o restaura em cada virtual thread. Assim os spans dos bureaus (RestClient auto-instrumentado) ficam filhos do span da request.

**Justificativa**: Isto preserva a regra de ouro: `credit-hub-application` continua **framework-free** — só enxerga `java.util.concurrent.ExecutorService` e `java.util.function.Supplier` (JDK puro). Todo o Micrometer fica confinado ao `bootstrap`. É exatamente o "executor context-aware proveniente do bootstrap" que a versão anterior desta decisão já apontava como saída correta. (As deps de Micrometer que uma sessão anterior havia adicionado ao módulo `application` — declaradas mas não usadas — foram removidas.)

**Trade-off / limitação conhecida**: A propagação depende do `ObservationThreadLocalAccessor` (registrado via ServiceLoader pelo `micrometer-observation`) estar ativo para carregar a `Observation` corrente; é o caminho padrão e confiável. Não há span manual "scatter-gather-bureaus" — a correlação vem da própria `Observation` da request restaurada nas threads. Validação fina (waterfall no Jaeger) só é observável em runtime com a stack de pé.

## 4. Pipeline de Observabilidade (OTel Collector + Prometheus + Jaeger)

**Contexto**: O Jaeger 1.60 (`jaegertracing/all-in-one`) não gera métricas RED (Rate, Errors, Duration) nativamente dos traces recebidos para popular a sua aba "Monitor" (SPM - Service Performance Monitoring). Ele apenas consome métricas já calculadas a partir de um backend compatível, como o Prometheus.

**A Decisão**: Em vez de enviar traces diretamente do Spring Boot para o Jaeger (`4318`), adicionamos um **OpenTelemetry Collector** no meio do caminho. O Collector intercepta os traces, calcula as métricas RED através do conector `spanmetrics`, envia as métricas para o **Prometheus**, e encaminha os traces originais para o **Jaeger**. O Jaeger foi então configurado para ler essas métricas do Prometheus. Também adicionamos o `micrometer-registry-prometheus` para expor as métricas internas da JVM e do Resilience4j diretamente para o Prometheus via `/actuator/prometheus`.

**Justificativa**: 
Esta é a arquitetura moderna recomendada pelo ecossistema OpenTelemetry para obter a aba Monitor (SPM) operante no Jaeger. O uso do OTel Collector desacopla a lógica de extração de métricas do código da aplicação, permitindo que a aplicação faça apenas um push (OTLP) e a infraestrutura cuide da derivação de métricas. A integração do Micrometer complementa a visão RED com métricas de infraestrutura (circuit breakers, db pools) no Prometheus. 

**Trade-off**: Maior complexidade na infraestrutura local (Docker Compose) devido à inclusão de dois novos componentes (Collector e Prometheus), exigindo também a configuração de normalização de queries no Jaeger (`PROMETHEUS_QUERY_NORMALIZE_CALLS`). O benefício é uma malha de telemetria rica e pronta para produção.

**Na nuvem (Azure)**: o Collector sobe como Container App próprio, lendo `infra/otel/otel-collector-config.cloud.yaml` (montado via Azure File). Lá não há Jaeger; o único destino é o exporter `azuremonitor` → **Application Insights**. As apps continuam fazendo push OTLP, só mudando o endpoint (`MANAGEMENT_OTLP_TRACING_ENDPOINT`) para o Collector interno. Escolhemos essa rota em vez do **agent Java do Application Insights** porque o agent **não suporta OTLP** e não coexiste com o exporter azuremonitor — brigaria com a instrumentação `micrometer-tracing-bridge-otel` já em uso.

## 5. Continuidade de trace no Outbox store-and-forward (traceparent persistido)

**Contexto**: O Outbox é store-and-forward: o `ConsultaController` grava a linha e **retorna**; o `OutboxRelay` publica ~2s depois numa thread do `@Scheduled`. Sem o `ThreadLocal` da request, o publish (e portanto `audit-service`/`decision-consumer`) nascia num **trace novo**, órfão da request original. É um problema de design, não de config.

**A Decisão**: Persistimos o contexto de trace na própria linha da outbox. O `OutboxWriter` (ainda dentro da request) captura o span corrente e grava a coluna `traceparent` no formato **W3C** (`00-<traceId>-<spanId>-<flags>`). O `OutboxRelay`, ao publicar, **re-hidrata** esse contexto (`tracer.traceContextBuilder()`) e cria o span de publish como **filho** dele, executando o `send` dentro desse escopo. A observação do Kafka é habilitada (`spring.kafka.template.observation-enabled` no produtor; `spring.kafka.listener.observation-enabled` nos consumers) para que o header traceparent seja injetado/lido.

**Gotcha**: a auto-instrumentação do produtor Kafka injeta o traceparent do contexto **atual**. Por isso re-hidratamos o contexto **antes** do `send` (escopo do span filho) em vez de escrever o header na mão — do contrário a instrumentação sobrescreveria com o contexto da thread do scheduler. Se não houver span na escrita, `traceparent` fica `null` e a publicação vira um trace novo (telemetria nunca quebra a request).

**Trade-off (conhecido)**: Optamos por **parent-child**, que dá uma waterfall única e legível no Jaeger/App Insights — com um "buraco" de ~2s correspondente ao intervalo de polling. A alternativa semanticamente mais correta para assíncrono seriam **span links** (o consumer não está causalmente *dentro* da request, que já retornou), mas perde-se a waterfall contínua. Escolhemos legibilidade; a alternativa fica registrada.

**Schema**: adiciona a coluna `traceparent` (nullable, 55 chars) na tabela `outbox`. Com `ddl-auto: update` a coluna nasce sozinha, sem migração manual — risco baixo por ser nullable e aditiva.
