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

## 6. Provider Confluent v2 e Schema Registry via Stream Governance (fix `404 Not Found`)

**Contexto**: O `terraform apply` falhava ao ler o Schema Registry (`data.confluent_schema_registry_cluster.sr` → `error reading Schema Registry Clusters: 404 Not Found`). Tentativas anteriores atacaram o sintoma errado: hardcode de região (`eastus`), e `time_sleep` de 45s→3m assumindo que era lentidão de provisionamento do Stream Governance.

**A Decisão**: Subir o provider Confluent de `~> 1.72.0` para `~> 2.0` (`providers.tf`) e rodar `terraform init -upgrade`.

**Justificativa**: O 404 **não era timing nem região** — era API morta. O provider 1.72 lê o Schema Registry pela API **`/srcm/v2`**, que a Confluent **removeu em 3 de maio de 2025** (retorna 404 para contas fora do allow-list). Esperar num endpoint desligado nunca resolveria. A partir do provider **v2**, a leitura usa a API `/srcm/v3`. O código já estava compatível com a v2 (o `data confluent_schema_registry_cluster` usa só `environment { id }`, sem os atributos `region`/`package` que a v2 removeu; o `confluent_schema_registry_region` — origem do 404 *original*, aquele sim de região não suportada em `brazilsouth` — já havia sido excluído). Cluster é `basic`, então as breaking changes de `byok`/`dedicated` da v2 não aplicam.

**Trade-off / nuance**: Houve **dois 404 distintos** confundidos ao longo da depuração: (a) o *original*, do `confluent_schema_registry_region`, era de fato região não suportada — resolvido **removendo** aquele data source; (b) o *atual*, do `confluent_schema_registry_cluster`, é a API srcm v2 desligada — resolvido pelo **bump de provider**. O `time_sleep.wait_for_sr` (3m) foi mantido: não resolvia o (b), mas protege contra corrida ao ler o SR logo após criar o environment. Pode ser reduzido depois.

## 7. Registry do ACR configurado pelo CD, não pelo Terraform (fix `IdentityDoesNotExist` no create do ACA)

**Contexto**: A criação dos Container Apps Spring falhava com `IdentityDoesNotExist: ... No managed service identities are associated with resource '.../containerApps/credithub-query-service'`. Note que o resource ID "não encontrado" no erro é o do **próprio Container App**, não o da UAI.

**A Decisão**: Remover os blocos `registry {}` dos Container Apps no Terraform (`azure_aca.tf`) e adicionar `registry` ao `ignore_changes`. O **CD** (`deploy.yml`) passa a configurar o pull do ACR via `az containerapp registry set --identity <UAI>` **depois** do create, antes do `az containerapp update --image`.

**Justificativa**: É um bug conhecido do lado do ACA ([azurerm#20675](https://github.com/hashicorp/terraform-provider-azurerm/issues/20675) / [azure-container-apps#1467](https://github.com/microsoft/azure-container-apps/issues/1467)): no **create**, a API valida o `registry.identity` **antes** de concluir a associação da User Assigned Identity ao app; sem UAI associada, cai no fallback de *system-assigned* (cujo "resource ID" é o do próprio app — exatamente o que o erro mostra) e falha. Descartamos as duas hipóteses erradas por evidência: **não é propagação** (um `time_sleep` de 60s após a UAI não mudou nada) e **não é versão do provider** (o changelog azurerm 3.100→3.116 não tem fix de identity em Container App). No 1º apply a imagem é o placeholder **público** (`mcr.microsoft.com/k8se/quickstart:latest`), que não precisa de credencial de registry nenhuma; no CD o app já existe e a UAI já está associada, então o `registry set` **não** dispara o bug. Mantém a arquitetura de managed identity (sem habilitar admin creds no ACR) e é coerente com o princípio já vigente de "CD é dono da imagem/pull".

**Trade-off**: O contrato do registry passa a ser dividido (TF ignora, CD configura) — mesma repartição já usada para a tag da imagem (`ignore_changes` no `image`). O `time_sleep.wait_for_identity` foi mantido porque ainda cobre a propagação da role `AcrPull` e da access policy do Key Vault. Alternativa descartada: ACR admin + `password_secret_name` — menor diff, mas regride segurança (credencial estática em vez de MI), contra a filosofia KV/MI do projeto.

**Correção (sessão posterior — segundo gatilho: `secret.identity` do Key Vault)**: A conclusão acima estava **incompleta**. O `IdentityDoesNotExist` tem **dois** gatilhos de referência à UAI no create — `registry.identity` (tratado acima) **e** `secret.identity` (KV refs). O segundo não apareceu na sessão do item 7 porque os Container Apps já existiam de tentativas anteriores: aquele `apply` era *update* (identity já associada → validação passa). Só num **create do zero** (pós-`destroy`) o `secret.identity` é validado num create genuíno e falha. O mecanismo é o mesmo: no `CreateOrUpdate` atômico o ACA resolve o `secret.identity` **antes** de persistir a associação da UAI; não achando identity no app, cai no fallback *system-assigned* (resource id = o próprio app) → `IdentityDoesNotExist`. Confirmado que **não é propagação**: `time_sleep` de 60s → 180s não mudou nada.

**Decisão (secrets)**: Passar os 3 secrets **inline** (`value = azurerm_key_vault_secret.<x>.value`) em vez de `key_vault_secret_id + identity`. Sem o campo `identity`, o ACA não resolve identity alguma no create → o gatilho some. Escolhido em vez de mover os secrets para o CD (que preservaria KV+MI em runtime, mas mexeria nos `env` que referenciam `secret_name` e adicionaria um ponto de falha no CD). **Custo/segurança**: perde-se o KV-via-Managed-Identity em *runtime* (o valor passa a viver na config da revisão, sem rotação sem redeploy); porém os valores **já estão no tfstate** (o TF os escreve no KV a partir dos outputs do Confluent e de `var.postgres_admin_password`), então inline **não expõe nada novo**. O KV segue como fonte populada pelo TF.

**Achado final (terceiro gatilho: o próprio bloco `identity {}`)**: Mesmo sem `registry.identity` e sem `secret.identity`, um `apply` do zero **ainda** falhava com `IdentityDoesNotExist` no *create* do `credithub-audit` — enquanto o *update* do `credithub-decision` (já existente) passava no mesmo `apply`. Por eliminação, o gatilho de fundo é **associar a UAI no create** (o bloco `identity { UserAssigned }`): o ACA valida/associa a UAI durante o `CreateOrUpdate` de um app novo e falha; em *update* a associação já existe e passa. Não é qual campo referencia a UAI — é a associação **no create**.

**Decisão final**: Remover **todo** vínculo com a UAI do Terraform (nem `identity {}`, nem registry, nem secret.identity) e adicionar `identity` ao `ignore_changes`. O **CD** (`deploy.yml`) passa a fazer `az containerapp identity assign` **e** `registry set` após o create — ambos são *updates* de um app existente, exatamente a operação que comprovadamente funciona. O create do TF fica 100% livre de UAI (placeholder público + secrets inline não precisam de identity). É a conclusão do mesmo princípio do registry: **nada que referencie a UAI no create; tudo no CD**. O `time_sleep.wait_for_identity` deixou de ser necessário para os apps (o CD roda muito depois, com a UAI já propagada), mas foi mantido por ora sem custo.
