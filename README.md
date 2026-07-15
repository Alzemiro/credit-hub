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
- **decision-consumer**: Consumidor de regras de negócio com resiliência baseada em @RetryableTopic (non-blocking retries) e envio para Dead Letter Topic (DLT) persistida em banco de dados.

## Arquitetura Cloud e Diagrama

O sistema foi desenhado para rodar na nuvem utilizando **Azure Container Apps** e **Confluent Cloud** para Kafka. 

```mermaid
graph TD
    Client((Client)) -->|POST /consultas| CQS[credit-query-service]
    
    subgraph "Azure Container Apps"
        CQS
        AUDIT[audit-service]
        DECISION[decision-consumer]
        OTEL[otel-collector]
        WIREMOCK[wiremock]
    end

    CQS -->|REST Síncrono| WIREMOCK
    CQS -->|Grava Outbox| DB_CQS[(PG: credithub)]
    CQS -.->|Outbox Relay| KAFKA
    
    KAFKA{Confluent Cloud Kafka}
    
    KAFKA -->|Consome Eventos| AUDIT
    KAFKA -->|Consome Eventos| DECISION
    
    AUDIT -->|Idempotência| DB_AUDIT[(PG: audit)]
    DECISION -->|Pipeline de Decisão| DB_DEC[(PG: decision)]

    CQS -.->|Push Traces OTLP| OTEL
    AUDIT -.->|Push Traces OTLP| OTEL
    DECISION -.->|Push Traces OTLP| OTEL
    
    OTEL -->|Exporta via azuremonitor| APPINSIGHTS(Azure App Insights)

    subgraph "Azure Postgres Flexible Server"
        DB_CQS
        DB_AUDIT
        DB_DEC
    end
```

## Tecnologias e Infraestrutura
- Java 21 + Virtual Threads + Spring Boot
- Resilience4j (Bulkhead, Circuit Breaker, Retry)
- PostgreSQL (Azure Flexible Server)
- Apache Kafka + Confluent Schema Registry (Avro) gerenciados na Confluent Cloud
- Observabilidade: OpenTelemetry Collector, Azure App Insights, Prometheus e Jaeger
- Testes de Carga: Grafana k6
- Infraestrutura como Código: **Terraform**

## Integração Cloud e CI/CD

A infraestrutura foi automatizada e separada da entrega do código da seguinte forma:

1. **Deploy da Infra (Terraform)**:
   O Terraform no diretório `infra/terraform` é responsável por provisionar o Azure (Resource Group, Key Vault, Postgres, Container Apps) e a Confluent Cloud.
   - **Gerenciamento de Estado**: Atualmente o estado do Terraform (`terraform.tfstate`) é mantido localmente. Por isso, **NÃO execute o Terraform Apply pelo GitHub Actions** sem antes migrar o backend para o Azure Storage. O apply deve ser feito da máquina de desenvolvimento.
   - **Imagem Placeholder**: No primeiro provisionamento, o Terraform sobe os Container Apps das aplicações com uma imagem pública "fantasma" (`mcr.microsoft.com/k8se/quickstart:latest`). Isso evita a dependência cíclica (o Container App falhar por não ter a imagem do Azure Container Registry ainda vazio).

2. **Deploy das Aplicações (GitHub Actions)**:
   A pipeline (`.github/workflows/deploy.yml`) assume o controle a partir daí. Ela constrói as imagens (via `bootBuildImage` do Gradle), manda para o ACR criado pelo Terraform e usa o `az containerapp update` para substituir a imagem placeholder pelas imagens finais em Java.

3. **Autenticação OIDC (Sem Senhas)**:
   O GitHub Actions se comunica com o Azure através do Microsoft Entra ID usando **OIDC (OpenID Connect)**. Nenhuma senha de *Service Principal* é estocada no GitHub. Os Container Apps puxam segredos diretamente do Azure Key Vault internamente usando identidades gerenciadas.

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
5. Em outra aba de terminal, execute o `decision-consumer`:
   ```bash
   ./gradlew :decision-consumer:bootRun
   ```

## Roadmap

- [x] Sprint 1-3: Espinha síncrona com Virtual Threads, Scatter-Gather e resiliência com Resilience4j
- [x] Sprint 4: Adicionado Transactional Outbox ao credit-query-service e serviço idempotente audit-service consumindo Kafka + Schema Registry.
- [x] Sprint 5: Criado decision-consumer com @RetryableTopic (retries não-bloqueantes com backoff) + persistência de Dead Letter Topic (DLT) no banco.
- [x] Sprint 6: Observabilidade (Tracing OTLP, Jaeger SPM e Prometheus) e automação de Testes de Carga (k6).
